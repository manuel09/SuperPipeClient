package org.schabi.newpipe.local.history;

/*
 * Copyright (C) Mauricio Colli 2018
 * HistoryRecordManager.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.feed.dao.FeedDAO;
import org.schabi.newpipe.database.history.dao.SearchHistoryDAO;
import org.schabi.newpipe.database.history.dao.StreamHistoryDAO;
import org.schabi.newpipe.database.history.model.SearchHistoryEntry;
import org.schabi.newpipe.database.history.model.StreamHistoryEntity;
import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity;
import org.schabi.newpipe.database.stream.StreamStatisticsEntry;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.dao.StreamStateDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.local.feed.FeedViewModel;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.util.ExtractorHelper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class HistoryRecordManager {
    private final AppDatabase database;
    private final StreamDAO streamTable;
    private final StreamHistoryDAO streamHistoryTable;
    private final SearchHistoryDAO searchHistoryTable;
    private final StreamStateDAO streamStateTable;
    private final SharedPreferences sharedPreferences;
    private final String searchHistoryKey;
    private final String streamHistoryKey;

    public HistoryRecordManager(final Context context) {
        database = NewPipeDatabase.getInstance(context);
        streamTable = database.streamDAO();
        streamHistoryTable = database.streamHistoryDAO();
        searchHistoryTable = database.searchHistoryDAO();
        streamStateTable = database.streamStateDAO();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        searchHistoryKey = context.getString(R.string.enable_search_history_key);
        streamHistoryKey = context.getString(R.string.enable_watch_history_key);
    }

    ///////////////////////////////////////////////////////
    // Watch History
    ///////////////////////////////////////////////////////

    /**
     * Marks a stream item as watched such that it is hidden from the feed if watched videos are
     * hidden. Adds a history entry and updates the stream progress to 100%.
     *
     * @see FeedDAO#getLiveOrNotPlayedStreams
     * @see FeedViewModel#togglePlayedItems
     * @param info the item to mark as watched
     * @return a Maybe containing the ID of the item if successful
     */
    public Maybe<Long> markAsWatched(final StreamInfoItem info) {
        if (!isStreamHistoryEnabled()) {
            return Maybe.empty();
        }

        final OffsetDateTime currentTime = OffsetDateTime.now(ZoneOffset.UTC);
        return Maybe.fromCallable(() -> database.runInTransaction(() -> {
            final long streamId;
            final long duration;
            // Duration will not exist if the item was loaded with fast mode, so fetch it if empty
            if (info.getDuration() < 0) {
                final StreamInfo completeInfo = ExtractorHelper.getStreamInfo(
                        info.getServiceId(),
                        info.getUrl(),
                        false
                )
                        .subscribeOn(Schedulers.io())
                        .blockingGet();
                duration = completeInfo.getDuration();
                streamId = streamTable.upsert(new StreamEntity(completeInfo));
            } else {
                duration = info.getDuration();
                streamId = streamTable.upsert(new StreamEntity(info));
            }

            // Update the stream progress to the full duration of the video
            final StreamStateEntity entity = new StreamStateEntity(
                    streamId,
                    duration * 1000
            );
            streamStateTable.upsert(entity);

            // Add a history entry
            final StreamHistoryEntity latestEntry = streamHistoryTable.getLatestEntry(streamId);
            if (latestEntry == null) {
                // never actually viewed: add history entry but with 0 views
                return streamHistoryTable.insert(new StreamHistoryEntity(streamId, currentTime, 0));
            } else {
                return 0L;
            }
        })).subscribeOn(Schedulers.io());
    }

    public Maybe<Long> onViewed(final StreamInfo info) {
        if (!isStreamHistoryEnabled()) {
            return Maybe.empty();
        }

        final OffsetDateTime currentTime = OffsetDateTime.now(ZoneOffset.UTC);
        return Maybe.fromCallable(() -> database.runInTransaction(() -> {
            final long streamId = streamTable.upsert(new StreamEntity(info));
            final StreamHistoryEntity latestEntry = streamHistoryTable.getLatestEntry(streamId);

            if (latestEntry != null) {
                streamHistoryTable.delete(latestEntry);
                latestEntry.setAccessDate(currentTime);
                latestEntry.setRepeatCount(latestEntry.getRepeatCount() + 1);
                return streamHistoryTable.insert(latestEntry);
            } else {
                // just viewed for the first time: set 1 view
                return streamHistoryTable.insert(new StreamHistoryEntity(streamId, currentTime, 1));
            }
        })).subscribeOn(Schedulers.io());
    }

    public Completable deleteStreamHistoryAndState(final long streamId) {
        return Completable.fromAction(() -> {
            streamStateTable.deleteState(streamId);
            streamHistoryTable.deleteStreamHistory(streamId);
        }).subscribeOn(Schedulers.io());
    }

    public Single<Integer> deleteWholeStreamHistory() {
        return Single.fromCallable(streamHistoryTable::deleteAll)
                .subscribeOn(Schedulers.io());
    }

    public Single<Integer> deleteCompleteStreamStateHistory() {
        return Single.fromCallable(streamStateTable::deleteAll)
                .subscribeOn(Schedulers.io());
    }

    public Flowable<List<StreamHistoryEntry>> getStreamHistory() {
        return streamHistoryTable.getHistory().subscribeOn(Schedulers.io());
    }

    public Flowable<List<StreamHistoryEntry>> getStreamHistorySortedById() {
        return streamHistoryTable.getHistorySortedById().subscribeOn(Schedulers.io());
    }

    public Flowable<List<StreamStatisticsEntry>> getStreamStatistics() {
        return streamHistoryTable.getStatistics().subscribeOn(Schedulers.io());
    }

    public Single<List<Long>> insertStreamHistory(final Collection<StreamHistoryEntry> entries) {
        final List<StreamHistoryEntity> entities = new ArrayList<>(entries.size());
        for (final StreamHistoryEntry entry : entries) {
            entities.add(entry.toStreamHistoryEntity());
        }
        return Single.fromCallable(() -> streamHistoryTable.insertAll(entities))
                .subscribeOn(Schedulers.io());
    }

    public Single<Integer> deleteStreamHistory(final Collection<StreamHistoryEntry> entries) {
        final List<StreamHistoryEntity> entities = new ArrayList<>(entries.size());
        for (final StreamHistoryEntry entry : entries) {
            entities.add(entry.toStreamHistoryEntity());
        }
        return Single.fromCallable(() -> streamHistoryTable.delete(entities))
                .subscribeOn(Schedulers.io());
    }

    private boolean isStreamHistoryEnabled() {
        return sharedPreferences.getBoolean(streamHistoryKey, false);
    }

    ///////////////////////////////////////////////////////
    // Search History
    ///////////////////////////////////////////////////////

    public Maybe<Long> onSearched(final int serviceId, final String search) {
        if (!isSearchHistoryEnabled()) {
            return Maybe.empty();
        }

        final OffsetDateTime currentTime = OffsetDateTime.now(ZoneOffset.UTC);
        final SearchHistoryEntry newEntry = new SearchHistoryEntry(currentTime, serviceId, search);

        return Maybe.fromCallable(() -> database.runInTransaction(() -> {
            // Check for any matching entry by search term only
            final SearchHistoryEntry existingEntry = searchHistoryTable.findBySearch(search);
            if (existingEntry != null) {
                existingEntry.setCreationDate(currentTime);
                return (long) searchHistoryTable.update(existingEntry);
            } else {
                return searchHistoryTable.insert(newEntry);
            }
        })).subscribeOn(Schedulers.io());
    }

    public Single<Integer> deleteSearchHistory(final String search) {
        return Single.fromCallable(() -> searchHistoryTable.deleteAllWhereQuery(search))
                .subscribeOn(Schedulers.io());
    }

    public Single<Integer> deleteCompleteSearchHistory() {
        return Single.fromCallable(searchHistoryTable::deleteAll)
                .subscribeOn(Schedulers.io());
    }

    public Flowable<List<String>> getRelatedSearches(final String query,
                                                     final int similarQueryLimit,
                                                     final int uniqueQueryLimit) {
        return query.length() > 0
                ? searchHistoryTable.getSimilarEntries(query, similarQueryLimit)
                : searchHistoryTable.getUniqueEntries(uniqueQueryLimit);
    }

    private boolean isSearchHistoryEnabled() {
        return sharedPreferences.getBoolean(searchHistoryKey, false);
    }

    ///////////////////////////////////////////////////////
    // Stream State History
    ///////////////////////////////////////////////////////

    public Maybe<StreamHistoryEntity> getStreamHistory(final StreamInfo info) {
        return Maybe.fromCallable(() -> {
            final long streamId = streamTable.upsert(new StreamEntity(info));
            return streamHistoryTable.getLatestEntry(streamId);
        }).subscribeOn(Schedulers.io());
    }

    public Maybe<StreamStateEntity> loadStreamState(final PlayQueueItem queueItem) {
        return queueItem.getStream()
                .map(info -> streamTable.upsert(new StreamEntity(info)))
                .flatMapPublisher(streamStateTable::getState)
                .firstElement()
                .flatMap(list -> list.isEmpty() ? Maybe.empty() : Maybe.just(list.get(0)))
                .filter(state -> state.isValid(queueItem.getDuration(), queueItem.isRoundPlayStream()))
                .subscribeOn(Schedulers.io());
    }

    public Maybe<StreamStateEntity> loadStreamState(final StreamInfo info) {
        return Single.fromCallable(() -> streamTable.upsert(new StreamEntity(info)))
                .flatMapPublisher(streamStateTable::getState)
                .firstElement()
                .flatMap(list -> list.isEmpty() ? Maybe.empty() : Maybe.just(list.get(0)))
                .filter(state -> state.isValid(info.getDuration(), info.isRoundPlayStream()))
                .subscribeOn(Schedulers.io());
    }

    public Completable saveStreamState(@NonNull final StreamInfo info, final long progressMillis) {
        return Completable.fromAction(() -> database.runInTransaction(() -> {
            final long streamId = streamTable.upsert(new StreamEntity(info));
            final StreamStateEntity state = new StreamStateEntity(streamId, progressMillis);
            if (!info.isRoundPlayStream() && state.isValid(info.getDuration(), info.isRoundPlayStream())) {
                streamStateTable.upsert(state);
            }
        })).subscribeOn(Schedulers.io());
    }

    public Single<StreamStateEntity[]> loadStreamState(final InfoItem info) {
        return Single.fromCallable(() -> {
            final List<StreamEntity> entities = streamTable
                    .getStream(info.getServiceId(), info.getUrl()).blockingFirst();
            if (entities.isEmpty()) {
                return new StreamStateEntity[]{null};
            }
            final List<StreamStateEntity> states = streamStateTable
                    .getState(entities.get(0).getUid()).blockingFirst();
            if (states.isEmpty()) {
                return new StreamStateEntity[]{null};
            }
            return new StreamStateEntity[]{states.get(0)};
        }).subscribeOn(Schedulers.io());
    }

    public Single<List<StreamStateEntity>> loadStreamStateBatch(final List<InfoItem> infos) {
        return Single.fromCallable(() -> {
            final List<StreamStateEntity> result = new ArrayList<>(infos.size());
            for (final InfoItem info : infos) {
                final List<StreamEntity> entities = streamTable
                        .getStream(info.getServiceId(), info.getUrl()).blockingFirst();
                if (entities.isEmpty()) {
                    result.add(null);
                    continue;
                }
                final List<StreamStateEntity> states = streamStateTable
                        .getState(entities.get(0).getUid()).blockingFirst();
                if (states.isEmpty()) {
                    result.add(null);
                } else {
                    result.add(states.get(0));
                }
            }
            return result;
        }).subscribeOn(Schedulers.io());
    }

    public Single<List<StreamStateEntity>> loadLocalStreamStateBatch(
            final List<? extends LocalItem> items) {
        return Single.fromCallable(() -> {
            final List<StreamStateEntity> result = new ArrayList<>(items.size());
            for (final LocalItem item : items) {
                final long streamId;
                if (item instanceof StreamStatisticsEntry) {
                    streamId = ((StreamStatisticsEntry) item).getStreamId();
                } else if (item instanceof PlaylistStreamEntity) {
                    streamId = ((PlaylistStreamEntity) item).getStreamUid();
                } else if (item instanceof PlaylistStreamEntry) {
                    streamId = ((PlaylistStreamEntry) item).getStreamId();
                } else {
                    result.add(null);
                    continue;
                }
                final List<StreamStateEntity> states = streamStateTable.getState(streamId)
                        .blockingFirst();
                if (states.isEmpty()) {
                    result.add(null);
                } else {
                    result.add(states.get(0));
                }
            }
            return result;
        }).subscribeOn(Schedulers.io());
    }

    ///////////////////////////////////////////////////////
    // Utility
    ///////////////////////////////////////////////////////

    public Single<Integer> removeOrphanedRecords() {
        return Single.fromCallable(streamTable::deleteOrphans).subscribeOn(Schedulers.io());
    }

}

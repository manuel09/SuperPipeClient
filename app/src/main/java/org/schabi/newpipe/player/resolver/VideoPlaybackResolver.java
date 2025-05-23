package org.schabi.newpipe.player.resolver;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.util.ListHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.google.android.exoplayer2.C.TIME_UNSET;
import static org.schabi.newpipe.util.ListHelper.*;

public class VideoPlaybackResolver implements PlaybackResolver {
    private static final String TAG = VideoPlaybackResolver.class.getSimpleName();

    @NonNull
    private final Context context;
    @NonNull
    private final PlayerDataSource dataSource;
    @NonNull
    private final QualityResolver qualityResolver;
    private SourceType streamSourceType;

    @Nullable
    private String playbackQuality;
    private List<String> blacklistUrls = new ArrayList<>();

    public enum SourceType {
        LIVE_STREAM,
        VIDEO_WITH_SEPARATED_AUDIO,
        VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
    }

    public VideoPlaybackResolver(@NonNull final Context context,
                                 @NonNull final PlayerDataSource dataSource,
                                 @NonNull final QualityResolver qualityResolver) {
        this.context = context;
        this.dataSource = dataSource;
        this.qualityResolver = qualityResolver;
    }

    @Override
    @Nullable
    public MediaSource resolve(@NonNull final StreamInfo info) {
        final MediaSource liveSource = PlaybackResolver.maybeBuildLiveMediaSource(dataSource, info);
        if (liveSource != null) {
            streamSourceType = SourceType.LIVE_STREAM;
            return liveSource;
        }

        final List<MediaSource> mediaSources = new ArrayList<>();
        final List<VideoStream> videoStreams = new ArrayList<>(info.getVideoStreams());
        final List<VideoStream> videoOnlyStreams = new ArrayList<>(info.getVideoOnlyStreams());

        removeTorrentStreams(videoStreams);
        removeTorrentStreams(videoOnlyStreams);

        // Create video stream source
        final List<VideoStream> videos = ListHelper.getSortedStreamVideosList(context,
                videoStreams, videoOnlyStreams, false, true)
                .stream().filter(s -> !blacklistUrls.contains(s.getContent())).collect(Collectors.toList());
        final int index;
        if (videos.isEmpty()) {
            index = -1;
        } else if (playbackQuality == null) {
            index = qualityResolver.getDefaultResolutionIndex(videos);
        } else {
            index = qualityResolver.getOverrideResolutionIndex(videos, getPlaybackQuality());
        }
        final MediaItemTag tag = StreamInfoTag.of(info, videos, index);
        @Nullable final VideoStream video = tag.getMaybeQuality()
                .map(MediaItemTag.Quality::getSelectedVideoStream)
                .orElse(null);

        if (video != null) {
            try {
                final MediaSource streamSource = PlaybackResolver.buildMediaSource(
                        dataSource, video, info, PlayerHelper.cacheKeyOf(info, video), tag);
                mediaSources.add(streamSource);
            } catch (final IOException e) {
                Log.e(TAG, "Unable to create video source:", e);
                return null;
            }
        }

        // Create optional audio stream source
        List<AudioStream> audioStreams = info.getAudioStreams()
                .stream().filter(s -> !blacklistUrls.contains(s.getContent())).collect(Collectors.toList());
        removeTorrentStreams(audioStreams);
        audioStreams = filterUnsupportedFormats(audioStreams, context);
        final AudioStream audio = audioStreams.isEmpty() ? null : audioStreams.get(
                ListHelper.getDefaultAudioFormat(context, audioStreams));

        // Use the audio stream if there is no video stream, or
        // merge with audio stream in case if video does not contain audio
        if (audio != null && (video == null || video.isVideoOnly())) {
            try {
                final MediaSource audioSource = PlaybackResolver.buildMediaSource(
                        dataSource, audio, info, PlayerHelper.cacheKeyOf(info, audio), tag);
                mediaSources.add(audioSource);
                streamSourceType = SourceType.VIDEO_WITH_SEPARATED_AUDIO;
            } catch (final IOException e) {
                Log.e(TAG, "Unable to create audio source:", e);
                return null;
            }
        } else {
            streamSourceType = SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY;
        }

        // If there is no audio or video sources, then this media source cannot be played back
        if (mediaSources.isEmpty()) {
            return null;
        }
        // Below are auxiliary media sources

        // Create subtitle sources
        final List<SubtitlesStream> subtitlesStreams = info.getSubtitles();
        if (subtitlesStreams != null) {
            // Torrent and non URL subtitles are not supported by ExoPlayer
            final List<SubtitlesStream> nonTorrentAndUrlStreams = removeNonUrlAndTorrentStreams(
                    subtitlesStreams);
            for (final SubtitlesStream subtitle : nonTorrentAndUrlStreams) {
                final MediaFormat mediaFormat = subtitle.getFormat();
                if (mediaFormat != null) {
                    @C.RoleFlags final int textRoleFlag = subtitle.isAutoGenerated()
                            ? C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
                            : C.ROLE_FLAG_CAPTION;
                    if(!subtitle.isUrl()){
                        final MediaItem.SubtitleConfiguration textMediaItem =
                                new MediaItem.SubtitleConfiguration.Builder(
                                        Uri.parse(""))
                                        .setMimeType(mediaFormat.getMimeType())
                                        .setRoleFlags(textRoleFlag)
                                        .setLanguage(PlayerHelper.captionLanguageOf(context, subtitle))
                                        .build();
                        final MediaSource textSource =
                                new SingleSampleMediaSource.Factory(new CustomDataSourceFactory(context, null, subtitle.getContent().getBytes()))
                                        .createMediaSource(textMediaItem, C.TIME_UNSET);
                        mediaSources.add(textSource);
                        continue;
                    }
                    final MediaItem.SubtitleConfiguration textMediaItem =
                            new MediaItem.SubtitleConfiguration.Builder(
                                    Uri.parse(subtitle.getContent()))
                                    .setMimeType(mediaFormat.getMimeType())
                                    .setRoleFlags(textRoleFlag)
                                    .setLanguage(PlayerHelper.captionLanguageOf(context, subtitle))
                                    .build();
                    final MediaSource textSource = dataSource.getSingleSampleMediaSourceFactory()
                            .createMediaSource(textMediaItem, TIME_UNSET);
                    mediaSources.add(textSource);
                }
            }
        }

        if (mediaSources.size() == 1) {
            return mediaSources.get(0);
        } else {
            return new MergingMediaSource(true, mediaSources.toArray(new MediaSource[0]));
        }
    }

    /**
     * Returns the last resolved {@link StreamInfo}'s {@link SourceType source type}.
     *
     * @return {@link Optional#empty()} if nothing was resolved, otherwise the {@link SourceType}
     * of the last resolved {@link StreamInfo} inside an {@link Optional}
     */
    public Optional<SourceType> getStreamSourceType() {
        return Optional.ofNullable(streamSourceType);
    }

    @Nullable
    public String getPlaybackQuality() {
        return playbackQuality;
    }

    public void setPlaybackQuality(@Nullable final String playbackQuality) {
        this.playbackQuality = playbackQuality;
    }

    public void addBlacklistUrl(@NonNull final String url) {
        blacklistUrls.add(url);
    }

    public List<String> getBlacklistUrls() {
        return blacklistUrls;
    }
}

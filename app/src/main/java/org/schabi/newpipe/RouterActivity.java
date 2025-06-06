package org.schabi.newpipe;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.AUDIO;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

import android.annotation.SuppressLint;
import android.app.IntentService;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.databinding.ListRadioIconItemBinding;
import org.schabi.newpipe.databinding.SingleChoiceDialogViewBinding;
import org.schabi.newpipe.download.DownloadDialog;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.ReCaptchaActivity;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.Info;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.StreamingService.LinkType;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.exceptions.*;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.ktx.ExceptionUtils;
import org.schabi.newpipe.local.dialog.PlaylistDialog;
import org.schabi.newpipe.player.MainPlayer;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.player.playqueue.ChannelPlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlaylistPlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PermissionHelper;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;
import org.schabi.newpipe.util.urlfinder.UrlFinder;
import org.schabi.newpipe.views.FocusOverlayView;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import icepick.Icepick;
import icepick.State;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Get the url from the intent and open it in the chosen preferred player.
 */
public class RouterActivity extends AppCompatActivity {
    protected final CompositeDisposable disposables = new CompositeDisposable();
    @State
    protected int currentServiceId = -1;
    @State
    protected LinkType currentLinkType;
    @State
    protected int selectedRadioPosition = -1;
    protected int selectedPreviously = -1;
    protected String currentUrl;
    private StreamingService currentService;
    private boolean selectionIsDownload = false;
    private boolean selectionIsAddToPlaylist = false;
    private AlertDialog alertDialogChoice = null;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Icepick.restoreInstanceState(this, savedInstanceState);

        if (TextUtils.isEmpty(currentUrl)) {
            currentUrl = getUrl(getIntent());

            if (TextUtils.isEmpty(currentUrl)) {
                handleText();
                finish();
            }
        }

        ThemeHelper.setDayNightMode(this);
        setTheme(ThemeHelper.isLightThemeSelected(this)
                ? R.style.RouterActivityThemeLight : R.style.RouterActivityThemeDark);
        Localization.assureCorrectAppLanguage(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // we need to dismiss the dialog before leaving the activity or we get leaks
        if (alertDialogChoice != null) {
            alertDialogChoice.dismiss();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        Icepick.saveInstanceState(this, outState);
    }

    @Override
    protected void onStart() {
        super.onStart();

        handleUrl(currentUrl);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        disposables.clear();
    }

    private void handleUrl(final String url) {
        disposables.add(Observable
                .fromCallable(() -> {
                    try {
                        if (currentServiceId == -1) {
                            currentService = NewPipe.getServiceByUrl(url);
                            currentServiceId = currentService.getServiceId();
                            currentLinkType = currentService.getLinkTypeByUrl(url);
                            currentUrl = url;
                        } else {
                            currentService = NewPipe.getService(currentServiceId);
                        }

                        // return whether the url was found to be supported or not
                        return currentLinkType != LinkType.NONE;
                    } catch (final ExtractionException e) {
                        // this can be reached only when the url is completely unsupported
                        return false;
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(isUrlSupported -> {
                    if (isUrlSupported) {
                        onSuccess();
                    } else {
                        showUnsupportedUrlDialog(url);
                    }
                }, throwable -> handleError(this, new ErrorInfo(throwable,
                        UserAction.SHARE_TO_NEWPIPE, "Getting service from url: " + url))));
    }

    /**
     * @param context the context. It will be {@code finish()}ed at the end of the handling if it is
     *                an instance of {@link RouterActivity}.
     * @param errorInfo the error information
     */
    private static void handleError(final Context context, final ErrorInfo errorInfo) {
        Throwable throwable = errorInfo.getThrowable();
        if (throwable != null) {
            throwable.printStackTrace();
        }

        if (throwable instanceof ReCaptchaException) {
            Toast.makeText(context, R.string.recaptcha_request_toast, Toast.LENGTH_LONG).show();
            // Starting ReCaptcha Challenge Activity
            final Intent intent = new Intent(context, ReCaptchaActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } else if (throwable != null
                && ExceptionUtils.isNetworkRelated(throwable)) {
            Toast.makeText(context, R.string.network_error, Toast.LENGTH_LONG).show();
        } else if (throwable instanceof AgeRestrictedContentException) {
            Toast.makeText(context, R.string.restricted_video_no_stream_new,
                    Toast.LENGTH_LONG).show();
        } else if (throwable instanceof GeographicRestrictionException) {
            Toast.makeText(context, R.string.georestricted_content, Toast.LENGTH_LONG).show();
        } else if (throwable instanceof LiveNotStartException) {
            Toast.makeText(context, R.string.live_not_started, Toast.LENGTH_LONG).show();
        } else if (throwable instanceof VideoNotReleaseException) {
            Toast.makeText(context, R.string.video_not_released, Toast.LENGTH_LONG).show();
        } else if (throwable instanceof PaidContentException) {
            Toast.makeText(context, R.string.paid_content_new, Toast.LENGTH_LONG).show();
        } else if (throwable instanceof PrivateContentException) {
            Toast.makeText(context, R.string.private_content_new, Toast.LENGTH_LONG).show();
        } else if (throwable instanceof SoundCloudGoPlusContentException) {
            Toast.makeText(context, R.string.soundcloud_go_plus_content_new,
                    Toast.LENGTH_LONG).show();
        } else if (throwable instanceof YoutubeMusicPremiumContentException) {
            Toast.makeText(context, R.string.youtube_music_premium_content_new,
                    Toast.LENGTH_LONG).show();
        } else if (throwable instanceof ContentNotAvailableException) {
            Toast.makeText(context, R.string.content_not_available, Toast.LENGTH_LONG).show();
        } else if (throwable instanceof ContentNotSupportedException) {
            Toast.makeText(context, R.string.content_not_supported_new, Toast.LENGTH_LONG).show();
        } else {
            ErrorUtil.createNotification(context, errorInfo);
        }

        if (context instanceof RouterActivity) {
            ((RouterActivity) context).finish();
        }
    }

    private void showUnsupportedUrlDialog(final String url) {
        final Context context = getThemeWrapperContext();
        new AlertDialog.Builder(context)
                .setTitle(R.string.unsupported_url)
                .setMessage(R.string.unsupported_url_dialog_message)
                .setIcon(R.drawable.ic_share)
                .setPositiveButton(R.string.open_in_browser,
                        (dialog, which) -> ShareUtils.openUrlInBrowser(this, url))
                .setNegativeButton(R.string.share,
                        (dialog, which) -> ShareUtils.shareText(this, "", url)) // no subject
                .setNeutralButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> finish())
                .show();
    }

    protected void onSuccess() {
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(this);
        final String selectedChoiceKey = preferences
                .getString(getString(R.string.preferred_open_action_key),
                        getString(R.string.preferred_open_action_default));

        final String showInfoKey = getString(R.string.show_info_key);
        final String videoPlayerKey = getString(R.string.video_player_key);
        final String backgroundPlayerKey = getString(R.string.background_player_key);
        final String popupPlayerKey = getString(R.string.popup_player_key);
        final String downloadKey = getString(R.string.download_key);
        final String addToPlaylistKey = getString(R.string.add_to_playlist_key);
        final String alwaysAskKey = getString(R.string.always_ask_open_action_key);

        if (selectedChoiceKey.equals(alwaysAskKey)) {
            final List<AdapterChoiceItem> choices
                    = getChoicesForService(currentService, currentLinkType);

            switch (choices.size()) {
                case 1:
                    handleChoice(choices.get(0).key);
                    break;
                case 0:
                    handleChoice(showInfoKey);
                    break;
                default:
                    showDialog(choices);
                    break;
            }
        } else if (selectedChoiceKey.equals(showInfoKey)) {
            handleChoice(showInfoKey);
        } else if (selectedChoiceKey.equals(downloadKey)) {
            handleChoice(downloadKey);
        } else if (selectedChoiceKey.equals(addToPlaylistKey)) {
            handleChoice(addToPlaylistKey);
        } else {
            final boolean isExtVideoEnabled = false;
            final boolean isExtAudioEnabled = preferences.getBoolean(
                    getString(R.string.use_external_audio_player_key), false);
            final boolean isVideoPlayerSelected = selectedChoiceKey.equals(videoPlayerKey)
                    || selectedChoiceKey.equals(popupPlayerKey);
            final boolean isAudioPlayerSelected = selectedChoiceKey.equals(backgroundPlayerKey);

            if (currentLinkType != LinkType.STREAM) {
                if (isExtAudioEnabled && isAudioPlayerSelected
                        || isExtVideoEnabled && isVideoPlayerSelected) {
                    Toast.makeText(this, R.string.external_player_unsupported_link_type,
                            Toast.LENGTH_LONG).show();
                    handleChoice(showInfoKey);
                    return;
                }
            }

            final List<StreamingService.ServiceInfo.MediaCapability> capabilities
                    = currentService.getServiceInfo().getMediaCapabilities();

            boolean serviceSupportsChoice = false;
            if (isVideoPlayerSelected) {
                serviceSupportsChoice = capabilities.contains(VIDEO);
            } else if (selectedChoiceKey.equals(backgroundPlayerKey)) {
                serviceSupportsChoice = capabilities.contains(AUDIO);
            }

            if (serviceSupportsChoice) {
                handleChoice(selectedChoiceKey);
            } else {
                handleChoice(showInfoKey);
            }
        }
    }

    private void showDialog(final List<AdapterChoiceItem> choices) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final Context themeWrapperContext = getThemeWrapperContext();

        final LayoutInflater inflater = LayoutInflater.from(themeWrapperContext);
        final RadioGroup radioGroup = SingleChoiceDialogViewBinding.inflate(getLayoutInflater())
                .list;

        final DialogInterface.OnClickListener dialogButtonsClickListener = (dialog, which) -> {
            final int indexOfChild = radioGroup.indexOfChild(
                    radioGroup.findViewById(radioGroup.getCheckedRadioButtonId()));
            final AdapterChoiceItem choice = choices.get(indexOfChild);

            handleChoice(choice.key);

            // open future streams always like this one, because "always" button was used by user
            if (which == DialogInterface.BUTTON_POSITIVE) {
                preferences.edit()
                        .putString(getString(R.string.preferred_open_action_key), choice.key)
                        .apply();
            }
        };

        alertDialogChoice = new AlertDialog.Builder(themeWrapperContext)
                .setTitle(R.string.preferred_open_action_share_menu_title)
                .setView(radioGroup)
                .setCancelable(true)
                .setNegativeButton(R.string.just_once, dialogButtonsClickListener)
                .setPositiveButton(R.string.always, dialogButtonsClickListener)
                .setOnDismissListener((dialog) -> {
                    if (!selectionIsDownload && !selectionIsAddToPlaylist) {
                        finish();
                    }
                })
                .create();

        //noinspection CodeBlock2Expr
        alertDialogChoice.setOnShowListener(dialog -> {
            setDialogButtonsState(alertDialogChoice, radioGroup.getCheckedRadioButtonId() != -1);
        });

        radioGroup.setOnCheckedChangeListener((group, checkedId) ->
                setDialogButtonsState(alertDialogChoice, true));
        final View.OnClickListener radioButtonsClickListener = v -> {
            final int indexOfChild = radioGroup.indexOfChild(v);
            if (indexOfChild == -1) {
                return;
            }

            selectedPreviously = selectedRadioPosition;
            selectedRadioPosition = indexOfChild;

            if (selectedPreviously == selectedRadioPosition) {
                handleChoice(choices.get(selectedRadioPosition).key);
            }
        };

        int id = 12345;
        for (final AdapterChoiceItem item : choices) {
            final RadioButton radioButton = ListRadioIconItemBinding.inflate(inflater).getRoot();
            radioButton.setText(item.description);
            TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(radioButton,
                    AppCompatResources.getDrawable(themeWrapperContext, item.icon),
                    null, null, null);
            radioButton.setChecked(false);
            radioButton.setId(id++);
            radioButton.setLayoutParams(new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            radioButton.setOnClickListener(radioButtonsClickListener);
            radioGroup.addView(radioButton);
        }

        if (selectedRadioPosition == -1) {
            final String lastSelectedPlayer = preferences.getString(
                    getString(R.string.preferred_open_action_last_selected_key), null);
            if (!TextUtils.isEmpty(lastSelectedPlayer)) {
                for (int i = 0; i < choices.size(); i++) {
                    final AdapterChoiceItem c = choices.get(i);
                    if (lastSelectedPlayer.equals(c.key)) {
                        selectedRadioPosition = i;
                        break;
                    }
                }
            }
        }

        selectedRadioPosition = Math.min(Math.max(-1, selectedRadioPosition), choices.size() - 1);
        if (selectedRadioPosition != -1) {
            ((RadioButton) radioGroup.getChildAt(selectedRadioPosition)).setChecked(true);
        }
        selectedPreviously = selectedRadioPosition;

        alertDialogChoice.show();

        if (DeviceUtils.isTv(this)) {
            FocusOverlayView.setupFocusObserver(alertDialogChoice);
        }
    }

    private List<AdapterChoiceItem> getChoicesForService(final StreamingService service,
                                                         final LinkType linkType) {
        final Context context = getThemeWrapperContext();

        final List<AdapterChoiceItem> returnList = new ArrayList<>();
        final List<StreamingService.ServiceInfo.MediaCapability> capabilities
                = service.getServiceInfo().getMediaCapabilities();

        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(this);
        final boolean isExtVideoEnabled = false;
        final boolean isExtAudioEnabled = preferences.getBoolean(
                getString(R.string.use_external_audio_player_key), false);

        final AdapterChoiceItem videoPlayer = new AdapterChoiceItem(
                getString(R.string.video_player_key), getString(R.string.video_player),
                R.drawable.ic_play_arrow);
        final AdapterChoiceItem showInfo = new AdapterChoiceItem(
                getString(R.string.show_info_key), getString(R.string.show_info),
                R.drawable.ic_info_outline);
        final AdapterChoiceItem popupPlayer = new AdapterChoiceItem(
                getString(R.string.popup_player_key), getString(R.string.popup_player),
                R.drawable.ic_picture_in_picture);
        final AdapterChoiceItem backgroundPlayer = new AdapterChoiceItem(
                getString(R.string.background_player_key), getString(R.string.background_player),
                R.drawable.ic_headset);
        final AdapterChoiceItem addToPlaylist = new AdapterChoiceItem(
                getString(R.string.add_to_playlist_key), getString(R.string.add_to_playlist),
                R.drawable.ic_add);


        if (linkType == LinkType.STREAM) {
            if (isExtVideoEnabled) {
                // show both "show info" and "video player", they are two different activities
                returnList.add(showInfo);
                returnList.add(videoPlayer);
            } else {
                final MainPlayer.PlayerType playerType = PlayerHolder.getInstance().getType();
                if (capabilities.contains(VIDEO)
                        && PlayerHelper.isAutoplayAllowedByUser(context)
                        && playerType == null || playerType == MainPlayer.PlayerType.VIDEO) {
                    // show only "video player" since the details activity will be opened and the
                    // video will be auto played there. Since "show info" would do the exact same
                    // thing, use that as a key to let VideoDetailFragment load the stream instead
                    // of using FetcherService (see comment in handleChoice())
                    returnList.add(new AdapterChoiceItem(
                            showInfo.key, videoPlayer.description, videoPlayer.icon));
                } else {
                    // show only "show info" if video player is not applicable, auto play is
                    // disabled or a video is playing in a player different than the main one
                    returnList.add(showInfo);
                }
            }

            if (capabilities.contains(VIDEO)) {
                returnList.add(popupPlayer);
            }
            if (capabilities.contains(AUDIO)) {
                returnList.add(backgroundPlayer);
            }
            // download is redundant for linkType CHANNEL AND PLAYLIST (till playlist downloading is
            // not supported )
            returnList.add(new AdapterChoiceItem(getString(R.string.download_key),
                    getString(R.string.download),
                    R.drawable.ic_file_download));

            // Add to playlist is not necessary for CHANNEL and PLAYLIST linkType since those can
            // not be added to a playlist
            returnList.add(addToPlaylist);

        } else {
            returnList.add(showInfo);
            if (capabilities.contains(VIDEO) && !isExtVideoEnabled) {
                returnList.add(videoPlayer);
                returnList.add(popupPlayer);
            }
            if (capabilities.contains(AUDIO) && !isExtAudioEnabled) {
                returnList.add(backgroundPlayer);
            }
        }

        return returnList;
    }

    private Context getThemeWrapperContext() {
        return new ContextThemeWrapper(this, ThemeHelper.isLightThemeSelected(this)
                ? R.style.LightTheme : R.style.DarkTheme);
    }

    private void setDialogButtonsState(final AlertDialog dialog, final boolean state) {
        final Button negativeButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        final Button positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (negativeButton == null || positiveButton == null) {
            return;
        }

        negativeButton.setEnabled(state);
        positiveButton.setEnabled(state);
    }

    private void handleText() {
        final String searchString = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        final int serviceId = getIntent().getIntExtra(Constants.KEY_SERVICE_ID, 0);
        final Intent intent = new Intent(getThemeWrapperContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        NavigationHelper.openSearch(getThemeWrapperContext(), serviceId, searchString);
    }

    private void handleChoice(final String selectedChoiceKey) {
        final List<String> validChoicesList = Arrays.asList(getResources()
                .getStringArray(R.array.preferred_open_action_values_list));
        if (validChoicesList.contains(selectedChoiceKey)) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString(getString(
                            R.string.preferred_open_action_last_selected_key), selectedChoiceKey)
                    .apply();
        }

        if (selectedChoiceKey.equals(getString(R.string.popup_player_key))
                && !PermissionHelper.isPopupEnabled(this)) {
            PermissionHelper.showPopupEnablementToast(this);
            finish();
            return;
        }

        if (selectedChoiceKey.equals(getString(R.string.download_key))) {
            if (PermissionHelper.checkStoragePermissions(this,
                    PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE)) {
                selectionIsDownload = true;
                openDownloadDialog();
            }
            return;
        }

        if (selectedChoiceKey.equals(getString(R.string.add_to_playlist_key))) {
            selectionIsAddToPlaylist = true;
            openAddToPlaylistDialog();
            return;
        }

        // stop and bypass FetcherService if InfoScreen was selected since
        // StreamDetailFragment can fetch data itself
        if (selectedChoiceKey.equals(getString(R.string.show_info_key))) {
            disposables.add(Observable
                    .fromCallable(() -> NavigationHelper.getIntentByLink(this, currentUrl))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(intent -> {
                        startActivity(intent);
                        finish();
                    }, throwable -> handleError(this, new ErrorInfo(throwable,
                            UserAction.SHARE_TO_NEWPIPE, "Starting info activity: " + currentUrl)))
            );
            return;
        }

        final Intent intent = new Intent(this, FetcherService.class);
        final Choice choice = new Choice(currentService.getServiceId(), currentLinkType,
                currentUrl, selectedChoiceKey);
        intent.putExtra(FetcherService.KEY_CHOICE, choice);
        startService(intent);

        finish();
    }

    private void openAddToPlaylistDialog() {
        // Getting the stream info usually takes a moment
        // Notifying the user here to ensure that no confusion arises
        Toast.makeText(
                getApplicationContext(),
                getString(R.string.processing_may_take_a_moment),
                Toast.LENGTH_SHORT)
                .show();

        disposables.add(ExtractorHelper.getStreamInfo(currentServiceId, currentUrl, false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        info -> PlaylistDialog.createCorrespondingDialog(
                                getThemeWrapperContext(),
                                Collections.singletonList(new StreamEntity(info)),
                                playlistDialog -> {
                                    playlistDialog.setOnDismissListener(dialog -> finish());

                                    playlistDialog.show(
                                            this.getSupportFragmentManager(),
                                            "addToPlaylistDialog"
                                    );
                                }
                        ),
                        throwable -> handleError(this, new ErrorInfo(
                                throwable,
                                UserAction.REQUESTED_STREAM,
                                "Tried to add " + currentUrl + " to a playlist",
                                currentService.getServiceId())
                        )
                )
        );
    }

    @SuppressLint("CheckResult")
    private void openDownloadDialog() {
        disposables.add(ExtractorHelper.getStreamInfo(currentServiceId, currentUrl, true)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    final List<VideoStream> sortedVideoStreams = ListHelper
                            .getSortedStreamVideosList(this, result.getVideoStreams(),
                                    result.getVideoOnlyStreams(), false, false);
                    final int selectedVideoStreamIndex = ListHelper
                            .getDefaultResolutionIndex(this, sortedVideoStreams);

                    final FragmentManager fm = getSupportFragmentManager();
                    final DownloadDialog downloadDialog = DownloadDialog.newInstance(result);
                    downloadDialog.setVideoStreams(sortedVideoStreams);
                    downloadDialog.setAudioStreams(result.getAudioStreams());
                    downloadDialog.setSelectedVideoStream(selectedVideoStreamIndex);
                    downloadDialog.setOnDismissListener(dialog -> finish());
                    downloadDialog.show(fm, "downloadDialog");
                    fm.executePendingTransactions();
                }, throwable ->
                        showUnsupportedUrlDialog(currentUrl)));
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
                                           @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (final int i : grantResults) {
            if (i == PackageManager.PERMISSION_DENIED) {
                finish();
                return;
            }
        }
        if (requestCode == PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE) {
            openDownloadDialog();
        }
    }

    private static class AdapterChoiceItem {
        final String description;
        final String key;
        @DrawableRes
        final int icon;

        AdapterChoiceItem(final String key, final String description, final int icon) {
            this.description = description;
            this.key = key;
            this.icon = icon;
        }
    }

    private static class Choice implements Serializable {
        final int serviceId;
        final String url;
        final String playerChoice;
        final LinkType linkType;

        Choice(final int serviceId, final LinkType linkType,
               final String url, final String playerChoice) {
            this.serviceId = serviceId;
            this.linkType = linkType;
            this.url = url;
            this.playerChoice = playerChoice;
        }

        @NonNull
        @Override
        public String toString() {
            return serviceId + ":" + url + " > " + linkType + " ::: " + playerChoice;
        }
    }

    public static class FetcherService extends IntentService {

        public static final String KEY_CHOICE = "key_choice";
        private static final int ID = 456;
        private Disposable fetcher;

        public FetcherService() {
            super(FetcherService.class.getSimpleName());
        }

        @Override
        public void onCreate() {
            super.onCreate();
            startForeground(ID, createNotification().build());
        }

        @Override
        protected void onHandleIntent(@Nullable final Intent intent) {
            if (intent == null) {
                return;
            }

            final Serializable serializable = intent.getSerializableExtra(KEY_CHOICE);
            if (!(serializable instanceof Choice)) {
                return;
            }
            final Choice playerChoice = (Choice) serializable;
            handleChoice(playerChoice);
        }

        public void handleChoice(final Choice choice) {
            Single<? extends Info> single = null;
            UserAction userAction = UserAction.SOMETHING_ELSE;

            switch (choice.linkType) {
                case STREAM:
                    single = ExtractorHelper.getStreamInfo(choice.serviceId, choice.url, false);
                    userAction = UserAction.REQUESTED_STREAM;
                    break;
                case CHANNEL:
                    single = ExtractorHelper.getChannelInfo(choice.serviceId, choice.url, false);
                    userAction = UserAction.REQUESTED_CHANNEL;
                    break;
                case PLAYLIST:
                    single = ExtractorHelper.getPlaylistInfo(choice.serviceId, choice.url, false);
                    userAction = UserAction.REQUESTED_PLAYLIST;
                    break;
            }


            if (single != null) {
                final UserAction finalUserAction = userAction;
                final Consumer<Info> resultHandler = getResultHandler(choice);
                fetcher = single
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(info -> {
                            resultHandler.accept(info);
                            if (fetcher != null) {
                                fetcher.dispose();
                            }
                        }, throwable -> handleError(this, new ErrorInfo(throwable, finalUserAction,
                                choice.url + " opened with " + choice.playerChoice,
                                choice.serviceId)));
            }
        }

        public Consumer<Info> getResultHandler(final Choice choice) {
            return info -> {
                final String videoPlayerKey = getString(R.string.video_player_key);
                final String backgroundPlayerKey = getString(R.string.background_player_key);
                final String popupPlayerKey = getString(R.string.popup_player_key);

                final SharedPreferences preferences = PreferenceManager
                        .getDefaultSharedPreferences(this);
                final boolean isExtVideoEnabled = false;
                final boolean isExtAudioEnabled = preferences.getBoolean(
                        getString(R.string.use_external_audio_player_key), false);

                final PlayQueue playQueue;
                if (info instanceof StreamInfo) {
                    if (choice.playerChoice.equals(backgroundPlayerKey) && isExtAudioEnabled) {
                        NavigationHelper.playOnExternalAudioPlayer(this, (StreamInfo) info);
                        return;
                    } else if (choice.playerChoice.equals(videoPlayerKey) && isExtVideoEnabled) {
                        NavigationHelper.playOnExternalVideoPlayer(this, (StreamInfo) info);
                        return;
                    }
                    playQueue = new SinglePlayQueue((StreamInfo) info);
                } else if (info instanceof ChannelInfo) {
                    playQueue = new ChannelPlayQueue((ChannelInfo) info);
                } else if (info instanceof PlaylistInfo) {
                    playQueue = new PlaylistPlayQueue((PlaylistInfo) info);
                } else {
                    return;
                }

                if (choice.playerChoice.equals(videoPlayerKey)) {
                    NavigationHelper.playOnMainPlayer(this, playQueue, false);
                } else if (choice.playerChoice.equals(backgroundPlayerKey)) {
                    NavigationHelper.playOnBackgroundPlayer(this, playQueue, true);
                } else if (choice.playerChoice.equals(popupPlayerKey)) {
                    NavigationHelper.playOnPopupPlayer(this, playQueue, true);
                }
            };
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            if (fetcher != null) {
                fetcher.dispose();
            }
        }

        private NotificationCompat.Builder createNotification() {
            return new NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.ic_pipepipe)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentTitle(
                            getString(R.string.preferred_player_fetcher_notification_title))
                    .setContentText(
                            getString(R.string.preferred_player_fetcher_notification_message));
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    @Nullable
    private String getUrl(final Intent intent) {
        String foundUrl = null;
        if (intent.getData() != null) {
            // Called from another app
            foundUrl = intent.getData().toString();
        } else if (intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
            // Called from the share menu
            final String extraText = intent.getStringExtra(Intent.EXTRA_TEXT);
            foundUrl = UrlFinder.firstUrlFromInput(extraText);
        }

        return foundUrl;
    }
}

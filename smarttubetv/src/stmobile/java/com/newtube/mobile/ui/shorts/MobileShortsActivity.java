package com.newtube.mobile.ui.shorts;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.GestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.player.Media3PlayerInitializer;
import com.newtube.mobile.player.Media3SourceFactory;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Full-screen vertical Shorts reel backed by NewTube's Media3 and MediaServiceCore. */
public class MobileShortsActivity extends AppCompatActivity {
    public static Intent intent(Context context) {
        return new Intent(context, MobileShortsActivity.class);
    }

    private final List<Video> mVideos = new ArrayList<>();
    private final CompositeDisposable mDisposables = new CompositeDisposable();
    private final Map<String, MediaItemFormatInfo> mPrefetchedFormats = new HashMap<>();
    private ViewPager2 mPager;
    private ShortsAdapter mAdapter;
    private ExoPlayer mPlayer;
    private DefaultTrackSelector mTrackSelector;
    private Media3SourceFactory mSourceFactory;
    private SeekBar mSeekBar;
    private TextView mSeekTime;
    private Handler mSeekHandler;
    private final Runnable mSeekTick = new Runnable() {
        @Override public void run() {
            if (mPlayer != null) {
                long duration = mPlayer.getDuration();
                if (!mScrubbing && duration > 0) {
                    mSeekBar.setMax((int) Math.min(duration, Integer.MAX_VALUE));
                    mSeekBar.setProgress((int) Math.min(mPlayer.getCurrentPosition(), Integer.MAX_VALUE));
                }
            }
            mSeekHandler.postDelayed(this, 250);
        }
    };
    private boolean mScrubbing;
    private int mCurrentIndex;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mobile_shorts);

        TextView header = findViewById(R.id.mobile_shorts_header);
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_nav_shorts_fill, 0, 0, 0);

        mPager = findViewById(R.id.mobile_shorts_pager);
        mPager.setOffscreenPageLimit(1);
        mPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        mAdapter = new ShortsAdapter();
        mPager.setAdapter(mAdapter);
        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                mCurrentIndex = position;
                if (position >= mVideos.size() - 4) {
                    loadNextShortsPage();
                }
                for (int i = position; i <= Math.min(position + 3, mVideos.size() - 1); i++) {
                    prefetch(i);
                }
                prepare(position);
            }
            @Override public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    prepare(mPager.getCurrentItem());
                } else if (state == ViewPager2.SCROLL_STATE_DRAGGING && mPlayer != null) {
                    mPlayer.setPlayWhenReady(false);
                }
            }
        });

        setupPlayer();
        setupSeekBar();
        setupBottomNav();
        loadShorts();
    }

    private void setupPlayer() {
        Media3PlayerInitializer initializer = new Media3PlayerInitializer(this);
        mSourceFactory = new Media3SourceFactory(this);
        mTrackSelector = initializer.createTrackSelector();
        mPlayer = initializer.createPlayer(mTrackSelector, mSourceFactory.getBandwidthMeter());
        mPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);

        String resolution = ShortsPrefs.getResolution(this);
        if (!ShortsPrefs.RESOLUTION_AUTO.equals(resolution)) {
            try {
                int maxSide = Integer.parseInt(resolution);
                mTrackSelector.setParameters(mTrackSelector.buildUponParameters()
                        .setMaxVideoSize(maxSide, maxSide));
            } catch (NumberFormatException ignored) {
            }
        }

        mPlayer.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                RecyclerView.ViewHolder holder =
                        ((RecyclerView) mPager.getChildAt(0)).findViewHolderForAdapterPosition(mCurrentIndex);
                if (holder instanceof ShortsViewHolder) {
                    ((ShortsViewHolder) holder).thumb.animate().alpha(0f).setDuration(120).start();
                }
            }
            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    mPlayer.setPlayWhenReady(true);
                }
            }
        });
    }

    private void setupSeekBar() {
        mSeekBar = findViewById(R.id.mobile_shorts_seek);
        mSeekTime = findViewById(R.id.mobile_shorts_seek_time);
        mSeekHandler = new Handler(Looper.getMainLooper());
        mSeekHandler.post(mSeekTick);

        mSeekBar.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    mScrubbing = true;
                    mSeekTime.setVisibility(View.VISIBLE);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mScrubbing = false;
                    mSeekTime.setVisibility(View.GONE);
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return false;
        });
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long duration = mPlayer != null ? mPlayer.getDuration() : 0;
                    mSeekTime.setText(formatTime(progress) + " / " + formatTime(duration));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                mScrubbing = true;
                mSeekTime.setVisibility(View.VISIBLE);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                mScrubbing = false;
                mSeekTime.setVisibility(View.GONE);
                if (mPlayer != null) {
                    mPlayer.seekTo(seekBar.getProgress());
                }
            }
        });
    }

    private void setupBottomNav() {
        BottomNavigationView nav = findViewById(R.id.mobile_shorts_bottom_nav);
        Menu menu = nav.getMenu();
        int home = 0x100001, shorts = 0x100002, subscriptions = 0x100003, downloads = 0x100004, you = 0x100005;
        menu.add(Menu.NONE, home, 0, R.string.header_home).setIcon(R.drawable.ic_nav_home);
        menu.add(Menu.NONE, shorts, 1, R.string.header_shorts).setIcon(R.drawable.ic_nav_shorts);
        menu.add(Menu.NONE, subscriptions, 2, R.string.header_subscriptions).setIcon(R.drawable.ic_nav_subscriptions);
        menu.add(Menu.NONE, downloads, 3, R.string.header_downloads).setIcon(R.drawable.ic_nav_downloads);
        menu.add(Menu.NONE, you, 4, R.string.mobile_nav_you).setIcon(R.drawable.ic_nav_you);
        nav.setSelectedItemId(shorts);
        nav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == shorts) return true;
            finish();
            startActivity(new Intent(this, com.newtube.mobile.ui.browse.MobileBrowseActivity.class));
            return true;
        });
    }

    private void loadShorts() {
        mDisposables.add(YouTubeServiceManager.instance().getContentService().getShortsObserve()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(group -> {
                    mShortsGroup = group;
                    VideoGroup videoGroup = VideoGroup.from(group, null, 0);
                    mVideos.clear();
                    if (videoGroup.getVideos() != null) mVideos.addAll(videoGroup.getVideos());
                    mAdapter.notifyDataSetChanged();
                    if (!mVideos.isEmpty()) prepare(0);
                }, error -> { }));
    }

    private MediaGroup mShortsGroup;

    private void loadNextShortsPage() {
        if (mShortsGroup == null) return;
        mDisposables.add(YouTubeServiceManager.instance().getContentService()
                .continueGroupObserve(mShortsGroup)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(group -> {
                    VideoGroup vg = VideoGroup.from(group, null, 0);
                    if (vg.getVideos() != null) {
                        int start = mVideos.size();
                        mVideos.addAll(vg.getVideos());
                        mAdapter.notifyItemRangeInserted(start, vg.getVideos().size());
                    }
                }, error -> { }));
    }

    private void prefetch(int position) {
        if (position < 0 || position >= mVideos.size()) return;
        Video video = mVideos.get(position);
        if (video == null || video.videoId == null || mPrefetchedFormats.containsKey(video.videoId)) return;
        mDisposables.add(YouTubeServiceManager.instance().getMediaItemService()
                .getFormatInfoObserve(video.videoId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(info -> mPrefetchedFormats.put(video.videoId, info),
                        error -> { }));
    }

    private void prepare(int position) {
        if (position < 0 || position >= mVideos.size() || mPlayer == null) return;
        Video video = mVideos.get(position);
        RecyclerView.ViewHolder holder =
                ((RecyclerView) mPager.getChildAt(0)).findViewHolderForAdapterPosition(position);
        if (holder instanceof ShortsViewHolder) {
            PlayerView view = ((ShortsViewHolder) holder).playerView;
            view.setPlayer(mPlayer);
        }
        mPlayer.stop();
        mPlayer.clearMediaItems();

        MediaItemFormatInfo info = mPrefetchedFormats.get(video.videoId);
        if (info != null) {
            setSource(info);
        } else {
            mDisposables.add(YouTubeServiceManager.instance().getMediaItemService()
                    .getFormatInfoObserve(video.videoId)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::setSource, error -> { }));
        }
    }

    private void setSource(MediaItemFormatInfo info) {
        try {
            if (info.containsDashFormats()) {
                mPlayer.setMediaSource(mSourceFactory.createDashSource(info));
            } else if (info.containsUrlFormats()) {
                mPlayer.setMediaSource(mSourceFactory.createUrlSource(info.createUrlList()));
            } else {
                return;
            }
            mPlayer.prepare();
            mPlayer.setPlayWhenReady(true);
        } catch (RuntimeException ignored) {
        }
    }

    private void setupActions(ShortsViewHolder holder, Video video) {
        holder.like.setOnClickListener(v -> toggleLike(video, holder));
        holder.dislike.setOnClickListener(v -> toggleDislike(video, holder));
        holder.comments.setOnClickListener(v -> {
            BrowsePresenter.instance(this).onVideoItemClicked(video);
        });
        holder.share.setOnClickListener(v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, "https://youtube.com/watch?v=" + video.videoId);
            startActivity(Intent.createChooser(share, getString(R.string.mobile_watch_share)));
        });
        holder.save.setOnClickListener(v -> toggleSave(video, holder));
        holder.overflow.setOnClickListener(v -> showOverflow(video));
        holder.subscribe.setOnClickListener(v -> toggleSubscribe(video, holder));
        GestureDetector gestures = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        mPlayer.setPlayWhenReady(!mPlayer.isPlaying());
                        return true;
                    }
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        toggleLike(video, holder);
                        return true;
                    }
                    @Override public void onLongPress(MotionEvent e) {
                        mPlayer.setPlaybackParameters(new androidx.media3.common.PlaybackParameters(2f));
                    }
                });
        holder.playerView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (mPlayer.getPlaybackParameters().speed != 1f) {
                    mPlayer.setPlaybackParameters(new androidx.media3.common.PlaybackParameters(1f));
                }
            }
            return gestures.onTouchEvent(event);
        });
    }

    private void toggleLike(Video video, ShortsViewHolder holder) {
        if (!YouTubeSignInService.instance().isSigned()) return;
        boolean active = holder.like.getTag() == Boolean.TRUE;
        MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
        if (video.mediaItem == null) return;
        mDisposables.add((active ? service.removeLikeObserve(video.mediaItem) : service.setLikeObserve(video.mediaItem))
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(unused -> {
                    holder.like.setTag(!active);
                    holder.like.setImageResource(active ? R.drawable.ic_shorts_like_outline : R.drawable.ic_shorts_like_fill);
                    if (!active) {
                        holder.dislike.setTag(false);
                        holder.dislike.setImageResource(R.drawable.ic_shorts_dislike_outline);
                    }
                }, error -> { }));
    }

    private void toggleDislike(Video video, ShortsViewHolder holder) {
        if (!YouTubeSignInService.instance().isSigned()) return;
        boolean active = holder.dislike.getTag() == Boolean.TRUE;
        MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
        if (video.mediaItem == null) return;
        mDisposables.add((active ? service.removeDislikeObserve(video.mediaItem) : service.setDislikeObserve(video.mediaItem))
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(unused -> {
                    holder.dislike.setTag(!active);
                    holder.dislike.setImageResource(active ? R.drawable.ic_shorts_dislike_outline : R.drawable.ic_shorts_dislike_fill);
                    if (!active) {
                        holder.like.setTag(false);
                        holder.like.setImageResource(R.drawable.ic_shorts_like_outline);
                    }
                }, error -> { }));
    }

    private void toggleSave(Video video, ShortsViewHolder holder) {
        boolean saved = holder.save.getTag() == Boolean.TRUE;
        if (saved) MediaServiceManager.instance().removeFromWatchLaterPlaylist(video);
        else MediaServiceManager.instance().addToWatchLaterPlaylist(video);
        holder.save.setTag(!saved);
        holder.save.setText(saved ? "Save" : "Saved");
        holder.save.setCompoundDrawablesWithIntrinsicBounds(0,
                saved ? R.drawable.ic_shorts_bookmark_outline : R.drawable.ic_shorts_bookmark_fill, 0, 0);
        holder.save.setBackgroundResource(saved ? 0 : R.drawable.mobile_pill_white);
        holder.save.setTextColor(saved ? 0xFFFFFFFF : 0xFF0F0F0F);
    }

    private void toggleSubscribe(Video video, ShortsViewHolder holder) {
        if (!YouTubeSignInService.instance().isSigned() || TextUtils.isEmpty(video.channelId)) return;
        boolean subscribed = holder.subscribe.getTag() == Boolean.TRUE;
        MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
        mDisposables.add((subscribed ? service.unsubscribeObserve(video.channelId) : service.subscribeObserve(video.channelId))
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(unused -> {
                    holder.subscribe.setTag(!subscribed);
                    holder.subscribe.setText(subscribed ? "Subscribe" : "Subscribed");
                    holder.subscribe.setBackgroundResource(subscribed ? R.drawable.mobile_pill_white : R.drawable.mobile_pill_white);
                }, error -> { }));
    }

    private void showOverflow(Video video) {
        List<String> labels = new ArrayList<>();
        labels.add("Quality");
        labels.add("Playback speed");
        if (VideoDownloads.canDownload(video)) labels.add("Save offline");
        labels.add("Open in regular player");
        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String choice = labels.get(which);
                    if ("Quality".equals(choice)) showQuality();
                    else if ("Playback speed".equals(choice)) showSpeed();
                    else if ("Save offline".equals(choice)) VideoDownloads.request(this, video);
                    else if ("Open in regular player".equals(choice)) BrowsePresenter.instance(this).onVideoItemClicked(video);
                })
                .show();
    }

    private void showQuality() {
        String[] choices = {"Auto", "1080p", "720p", "480p", "360p"};
        new AlertDialog.Builder(this).setTitle("Shorts quality")
                .setItems(choices, (d, i) -> {
                    String value = i == 0 ? ShortsPrefs.RESOLUTION_AUTO : choices[i].replace("p", "");
                    ShortsPrefs.setResolution(this, value);
                    if (i == 0) {
                        mTrackSelector.setParameters(mTrackSelector.buildUponParameters().clearVideoSizeConstraints());
                    } else {
                        int max = Integer.parseInt(value);
                        mTrackSelector.setParameters(mTrackSelector.buildUponParameters().setMaxVideoSize(max, max));
                    }
                }).show();
    }

    private void showSpeed() {
        String[] choices = {"1x", "1.25x", "1.5x", "2x"};
        new AlertDialog.Builder(this).setTitle("Playback speed")
                .setItems(choices, (d, i) -> {
                    float speed = i == 0 ? 1f : i == 1 ? 1.25f : i == 2 ? 1.5f : 2f;
                    mPlayer.setPlaybackParameters(new androidx.media3.common.PlaybackParameters(speed));
                }).show();
    }

    private String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long seconds = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    @Override protected void onResume() {
        super.onResume();
        if (mPlayer != null) mPlayer.setPlayWhenReady(true);
    }
    @Override protected void onPause() {
        if (mPlayer != null) mPlayer.setPlayWhenReady(false);
        super.onPause();
    }
    @Override protected void onDestroy() {
        mSeekHandler.removeCallbacks(mSeekTick);
        mDisposables.dispose();
        if (mPlayer != null) mPlayer.release();
        super.onDestroy();
    }

    private final class ShortsAdapter extends RecyclerView.Adapter<ShortsViewHolder> {
        @NonNull @Override public ShortsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mobile_shorts, parent, false);
            return new ShortsViewHolder(view);
        }
        @Override public void onBindViewHolder(@NonNull ShortsViewHolder holder, int position) {
            Video video = mVideos.get(position);
            holder.thumb.setAlpha(1f);
            Glide.with(holder.itemView).load(video.getCardImageUrl()).centerCrop().into(holder.thumb);
            holder.caption.setText(TextUtils.isEmpty(video.getTitleFull()) ? video.getTitle() : video.getTitleFull());
            holder.handle.setText(TextUtils.isEmpty(video.getAuthor()) ? "@channel" : "@" + video.getAuthor());
            if (!TextUtils.isEmpty(video.channelId)) {
                holder.subscribe.setTag(video.isSubscribed);
                holder.subscribe.setText(video.isSubscribed ? "Subscribed" : "Subscribe");
            }
            setupActions(holder, video);
            mDisposables.add(YouTubeServiceManager.instance().getMediaItemService()
                    .getMetadataObserve(video.videoId, video.getPlaylistId(), video.playlistIndex, video.playlistParams)
                    .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                    .subscribe(metadata -> bindMetadata(holder, video, metadata), error -> { }));
        }
        @Override public int getItemCount() { return mVideos.size(); }
    }

    private void bindMetadata(ShortsViewHolder holder, Video video, MediaItemMetadata metadata) {
        if (!TextUtils.isEmpty(metadata.getAuthor())) holder.handle.setText("@" + metadata.getAuthor());
        if (!TextUtils.isEmpty(metadata.getTitle())) holder.caption.setText(metadata.getTitle());
        if (!TextUtils.isEmpty(metadata.getChannelId())) video.channelId = metadata.getChannelId();
        if (!TextUtils.isEmpty(metadata.getAuthorImageUrl())) {
            Glide.with(holder.itemView).load(metadata.getAuthorImageUrl()).circleCrop().into(holder.avatar);
        }
        boolean subscribed = metadata.isSubscribed();
        holder.subscribe.setTag(subscribed);
        holder.subscribe.setText(subscribed ? "Subscribed" : "Subscribe");
        boolean liked = metadata.getLikeStatus() == MediaItemMetadata.LIKE_STATUS_LIKE;
        boolean disliked = metadata.getLikeStatus() == MediaItemMetadata.LIKE_STATUS_DISLIKE;
        holder.like.setTag(liked);
        holder.dislike.setTag(disliked);
        holder.like.setImageResource(liked ? R.drawable.ic_shorts_like_fill : R.drawable.ic_shorts_like_outline);
        holder.dislike.setImageResource(disliked ? R.drawable.ic_shorts_dislike_fill : R.drawable.ic_shorts_dislike_outline);
        mDisposables.add(YouTubeServiceManager.instance().getMediaItemService()
                .getPlaylistsInfoObserve(video.videoId)
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(infos -> {
                    if (infos != null && !infos.isEmpty()) {
                        boolean saved = infos.get(0).isSelected();
                        holder.save.setTag(saved);
                        holder.save.setText(saved ? "Saved" : "Save");
                        holder.save.setCompoundDrawablesWithIntrinsicBounds(0,
                                saved ? R.drawable.ic_shorts_bookmark_fill : R.drawable.ic_shorts_bookmark_outline, 0, 0);
                        holder.save.setBackgroundResource(saved ? R.drawable.mobile_pill_white : 0);
                        holder.save.setTextColor(saved ? 0xFF0F0F0F : 0xFFFFFFFF);
                    }
                }, error -> { }));
    }

    private final class ShortsViewHolder extends RecyclerView.ViewHolder {
        final PlayerView playerView;
        final ImageView thumb;
        final ImageView avatar;
        final TextView handle;
        final TextView subscribe;
        final TextView caption;
        final ImageButton like;
        final ImageButton dislike;
        final TextView comments;
        final TextView share;
        final TextView save;
        final TextView overflow;
        ShortsViewHolder(@NonNull View itemView) {
            super(itemView);
            playerView = itemView.findViewById(R.id.mobile_shorts_player);
            thumb = itemView.findViewById(R.id.mobile_shorts_thumbnail);
            avatar = itemView.findViewById(R.id.mobile_shorts_avatar);
            handle = itemView.findViewById(R.id.mobile_shorts_handle);
            subscribe = itemView.findViewById(R.id.mobile_shorts_subscribe);
            caption = itemView.findViewById(R.id.mobile_shorts_caption);
            like = itemView.findViewById(R.id.mobile_shorts_like);
            dislike = itemView.findViewById(R.id.mobile_shorts_dislike);
            comments = itemView.findViewById(R.id.mobile_shorts_comments);
            share = itemView.findViewById(R.id.mobile_shorts_share);
            save = itemView.findViewById(R.id.mobile_shorts_save);
            overflow = itemView.findViewById(R.id.mobile_shorts_overflow);
        }
    }
}

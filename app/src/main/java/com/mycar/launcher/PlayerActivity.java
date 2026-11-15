package com.mycar.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Плеер на весь экран: три обложки по центру, внизу кнопки и «волна», справа папки и треки. */
public class PlayerActivity extends Activity {

    private static final int T_UP = 0;
    private static final int T_FOLDER = 1;
    private static final int T_AUDIO = 2;
    private static final int T_VIDEO = 3;
    private static final int REQ_STORAGE = 21;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int LIGHT = 0xFFC9D0DC;
    private static final int MUTED = 0xFF8F98A8;
    private static final int ACCENT = 0xFFFF5A6A;

    private static final String[] AUDIO_EXT = {"mp3", "m4a", "aac", "flac", "wav", "ogg", "opus"};
    private static final String[] VIDEO_EXT = {"mp4", "m4v", "mkv", "avi", "3gp", "webm", "mov", "ts"};
    private static final String[] JUNK_DIRS = {"android", "lost.dir", "system volume information", "$recycle.bin"};
    private static final String[] JUNK_ROOTS = {"emulated", "self", "obb", "asec", "secure", "runtime",
            "user", "shell", "knox-emulated", "container", "sdcard"};

    private static final class Entry {
        final int type;
        final String name;
        final File file;

        Entry(int type, String name, File file) {
            this.type = type;
            this.name = name;
            this.file = file;
        }
    }

    private final Handler handler = new Handler();
    private final List<Entry> entries = new ArrayList<>();
    private final List<File> roots = new ArrayList<>();
    private final ArtCache artCache = new ArtCache();
    private SharedPreferences prefs;
    private float u;

    private ListView listView;
    private FileAdapter adapter;
    private File currentDir;
    private boolean videoMode;
    private boolean fullscreen;

    private View rail;
    private View rightPanel;
    private ImageView rMusic;
    private ImageView rVideo;
    private TextView pathView;
    private TextView tabMusic;
    private TextView tabVideo;
    private FrameLayout videoArea;
    private SurfaceView surface;
    private TextView npTitle;
    private TextView npArtist;
    private TextView tCur;
    private TextView tTotal;
    private WaveView wave;
    private ImageView bShuffle;
    private ImageView bPlay;
    private ImageView bRepeat;

    // cv[0] — предыдущий, cv[1] — следующий, cv[2] — текущий
    private final ImageView[] cv = new ImageView[3];
    private final Bitmap[] shown = new Bitmap[3];
    private boolean[] coverInit = new boolean[3];
    private final float[] baseX = new float[3];
    private int curSize;
    private int sideSize;
    private int lastW;
    private int lastH;

    private int lastIndex = -2;
    private int lastNext = -1;
    private int lastPrev = -1;
    private String lastPath = "";
    private int shownPlaying = -1;
    private int shownRepeat = -1;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updatePanel();
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        u = getResources().getDisplayMetrics().heightPixels / 720f;

        listView = findViewById(R.id.list);
        rail = findViewById(R.id.rail);
        rightPanel = findViewById(R.id.right_panel);
        rMusic = findViewById(R.id.r_music);
        rVideo = findViewById(R.id.r_video);
        pathView = findViewById(R.id.path);
        tabMusic = findViewById(R.id.tab_music);
        tabVideo = findViewById(R.id.tab_video);
        videoArea = findViewById(R.id.video_area);
        surface = findViewById(R.id.surface);
        npTitle = findViewById(R.id.np_title);
        npArtist = findViewById(R.id.np_artist);
        tCur = findViewById(R.id.t_cur);
        tTotal = findViewById(R.id.t_total);
        wave = findViewById(R.id.wave);
        bShuffle = findViewById(R.id.b_shuffle);
        bPlay = findViewById(R.id.b_play);
        bRepeat = findViewById(R.id.b_repeat);
        cv[0] = findViewById(R.id.cv_prev);
        cv[1] = findViewById(R.id.cv_next);
        cv[2] = findViewById(R.id.cv_cur);

        for (int k = 0; k < 3; k++) {
            cv[k].setBackgroundResource(k == 2 ? R.drawable.cover_bg_cur : R.drawable.cover_bg);
            cv[k].setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            cv[k].setClipToOutline(true);
        }

        applySizes();
        setupControls();

        videoMode = getIntent().getBooleanExtra("video", prefs.getBoolean("player_video", false));
        String last = prefs.getString("player_dir", null);
        if (last != null && new File(last).isDirectory()) currentDir = new File(last);

        adapter = new FileAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onEntry(position);
            }
        });

        ensureStorageAccess();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRoots();
        if (currentDir != null && !currentDir.isDirectory()) currentDir = null;
        reload();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            refreshRoots();
            reload();
        }
    }

    private void ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.pl_no_access, Toast.LENGTH_LONG).show();
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    // ---------- оформление и кнопки ----------

    private void px(TextView t, float size) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
    }

    private void size(View v, float s) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = Math.round(s * u);
        lp.height = Math.round(s * u);
        v.setLayoutParams(lp);
    }

    private void icon(int id, float s, int color) {
        ImageView v = findViewById(id);
        int pad = Math.round(s * u * 0.2f);
        size(v, s);
        v.setPadding(pad, pad, pad, pad);
        v.setColorFilter(color);
    }

    private void applySizes() {
        px(npTitle, 30);
        px(npArtist, 18);
        px(tCur, 14);
        px(tTotal, 14);
        px(tabMusic, 17);
        px(tabVideo, 17);
        px(pathView, 13);

        icon(R.id.r_home, 46, MUTED);
        icon(R.id.r_music, 46, MUTED);
        icon(R.id.r_video, 46, MUTED);
        icon(R.id.b_shuffle, 54, LIGHT);
        icon(R.id.b_prev, 58, LIGHT);
        icon(R.id.b_play, 70, ACCENT);
        icon(R.id.b_next, 58, LIGHT);
        icon(R.id.b_repeat, 54, LIGHT);
        icon(R.id.b_full, 54, LIGHT);

        wave.setBarHeight(Math.round(40 * u));
    }

    private void setupControls() {
        findViewById(R.id.r_home).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        rMusic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setVideoMode(false);
            }
        });
        rVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setVideoMode(true);
            }
        });
        tabMusic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setVideoMode(false);
            }
        });
        tabVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setVideoMode(true);
            }
        });
        findViewById(R.id.b_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlayerControl.previous();
            }
        });
        bPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlayerControl.toggle();
            }
        });
        findViewById(R.id.b_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlayerControl.next();
            }
        });
        bShuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlayerControl.toggleShuffle();
                updatePanel();
            }
        });
        bRepeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlayerControl.cycleRepeat();
                updatePanel();
            }
        });
        findViewById(R.id.b_full).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fullscreen = !fullscreen;
                int vis = fullscreen ? View.GONE : View.VISIBLE;
                rail.setVisibility(vis);
                rightPanel.setVisibility(vis);
            }
        });

        wave.setListener(new WaveView.Listener() {
            @Override
            public void onSeek(float fraction) {
                PlayerControl.seekTo(Math.round(fraction * PlayerState.durationMs));
            }
        });

        // Листание обложек: влево — следующий трек, вправо — предыдущий; нажатие по боковой обложке тоже листает
        videoArea.setOnTouchListener(new View.OnTouchListener() {
            private float downX;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (PlayerState.active && PlayerState.video) return false;
                int a = e.getAction();
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                } else if (a == MotionEvent.ACTION_UP) {
                    float dx = e.getX() - downX;
                    float limit = 40 * u;
                    if (dx < -limit) {
                        PlayerControl.next();
                    } else if (dx > limit) {
                        PlayerControl.previous();
                    } else {
                        onCoverTap(e.getX());
                    }
                }
                return true;
            }
        });

        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                PlayerControl.setDisplay(holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // размер подгоняется в updatePanel
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                PlayerControl.setDisplay(null);
            }
        });
    }

    private void onCoverTap(float x) {
        float mid = videoArea.getWidth() / 2f;
        float half = curSize / 2f;
        if (x < mid - half) {
            PlayerControl.previous();
        } else if (x > mid + half) {
            PlayerControl.next();
        } else {
            PlayerControl.toggle();
        }
    }

    private void setVideoMode(boolean video) {
        videoMode = video;
        prefs.edit().putBoolean("player_video", video).apply();
        reload();
    }

    // ---------- обзор папок ----------

    private static boolean hasExt(String name, String[] list) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : list) {
            if (e.equals(ext)) return true;
        }
        return false;
    }

    private static boolean isJunk(String name, String[] list) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String j : list) {
            if (j.equals(n)) return true;
        }
        return false;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void addRoot(String path, Set<String> seen) {
        File f = new File(path);
        String key;
        try {
            key = f.getCanonicalPath();
        } catch (IOException e) {
            key = path;
        }
        if (seen.contains(key) || !f.isDirectory() || !f.canRead() || f.list() == null) return;
        seen.add(key);
        roots.add(f);
    }

    /** Ищем флешки, карты и внутреннюю память. */
    private void refreshRoots() {
        roots.clear();
        Set<String> seen = new HashSet<>();

        File[] volumes = getExternalFilesDirs(null);
        if (volumes != null) {
            for (File d : volumes) {
                if (d == null) continue;
                String p = d.getAbsolutePath();
                int i = p.indexOf("/Android/");
                if (i > 0) addRoot(p.substring(0, i), seen);
            }
        }
        addRoot(Environment.getExternalStorageDirectory().getAbsolutePath(), seen);

        String[] bases = {"/storage", "/mnt"};
        for (String base : bases) {
            File[] kids = new File(base).listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (isJunk(k.getName(), JUNK_ROOTS)) continue;
                addRoot(k.getAbsolutePath(), seen);
            }
        }
    }

    private String labelFor(File root) {
        String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (root.getAbsolutePath().equals(internal)) return getString(R.string.pl_internal);
        return getString(R.string.pl_usb) + ": " + root.getName();
    }

    private boolean isRoot(File f) {
        for (File r : roots) {
            if (r.getAbsolutePath().equals(f.getAbsolutePath())) return true;
        }
        return false;
    }

    private void reload() {
        entries.clear();
        tabMusic.setBackgroundResource(videoMode ? R.drawable.pl_tab : R.drawable.pl_tab_on);
        tabVideo.setBackgroundResource(videoMode ? R.drawable.pl_tab_on : R.drawable.pl_tab);
        tabMusic.setTextColor(videoMode ? LIGHT : ACCENT);
        tabVideo.setTextColor(videoMode ? ACCENT : LIGHT);
        rMusic.setBackgroundResource(videoMode ? 0 : R.drawable.pl_tab_on);
        rVideo.setBackgroundResource(videoMode ? R.drawable.pl_tab_on : 0);
        rMusic.setColorFilter(videoMode ? MUTED : ACCENT);
        rVideo.setColorFilter(videoMode ? ACCENT : MUTED);

        if (currentDir == null) {
            pathView.setText(R.string.pl_roots);
            for (File r : roots) entries.add(new Entry(T_FOLDER, labelFor(r), r));
        } else {
            pathView.setText(currentDir.getAbsolutePath());
            entries.add(new Entry(T_UP, getString(R.string.pl_up), null));

            List<File> dirs = new ArrayList<>();
            List<File> media = new ArrayList<>();
            File[] files = currentDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    if (n.startsWith(".")) continue;
                    if (f.isDirectory()) {
                        if (!isJunk(n, JUNK_DIRS)) dirs.add(f);
                    } else if (hasExt(n, videoMode ? VIDEO_EXT : AUDIO_EXT)) {
                        media.add(f);
                    }
                }
            }
            Comparator<File> byName = new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            };
            Collections.sort(dirs, byName);
            Collections.sort(media, byName);
            for (File d : dirs) entries.add(new Entry(T_FOLDER, d.getName(), d));
            for (File m : media) {
                entries.add(new Entry(videoMode ? T_VIDEO : T_AUDIO, stripExt(m.getName()), m));
            }
            if (dirs.isEmpty() && media.isEmpty()) {
                Toast.makeText(this, R.string.pl_empty, Toast.LENGTH_SHORT).show();
            }
        }
        prefs.edit().putString("player_dir", currentDir == null ? null : currentDir.getAbsolutePath()).apply();
        adapter.notifyDataSetChanged();
        listView.setSelection(0);
    }

    private void onEntry(int position) {
        if (position < 0 || position >= entries.size()) return;
        Entry e = entries.get(position);
        if (e.type == T_UP) {
            if (currentDir == null) return;
            File parent = currentDir.getParentFile();
            currentDir = (isRoot(currentDir) || parent == null) ? null : parent;
            reload();
        } else if (e.type == T_FOLDER) {
            currentDir = e.file;
            reload();
        } else {
            List<Track> queue = new ArrayList<>();
            int start = 0;
            for (Entry other : entries) {
                if (other.type != e.type) continue;
                if (other == e) start = queue.size();
                queue.add(new Track(other.file.getAbsolutePath(), other.name, other.type == T_VIDEO));
            }
            PlayerControl.play(this, queue, start);
        }
    }

    private final class FileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null
                    ? convertView
                    : getLayoutInflater().inflate(R.layout.item_file, parent, false);
            Entry e = entries.get(position);

            ImageView icon = v.findViewById(R.id.file_icon);
            int res;
            if (e.type == T_UP) {
                res = R.drawable.ic_back;
            } else if (e.type == T_FOLDER) {
                res = R.drawable.ic_folder;
            } else if (e.type == T_VIDEO) {
                res = R.drawable.ic_video;
            } else {
                res = R.drawable.ic_music;
            }
            icon.setImageResource(res);
            icon.setColorFilter(MUTED);
            size(icon, 28);

            boolean playingNow = (e.type == T_AUDIO || e.type == T_VIDEO)
                    && e.file != null && e.file.getAbsolutePath().equals(PlayerState.currentPath);
            v.setBackgroundResource(playingNow ? R.drawable.pl_row_on : 0);
            if (playingNow) icon.setColorFilter(ACCENT);

            TextView name = v.findViewById(R.id.file_name);
            px(name, 18);
            name.setText(e.name);
            return v;
        }
    }

    // ---------- обложки ----------

    /** Размеры обложек считаем от свободного места: текущая крупная, боковые поменьше. */
    private void layoutCovers() {
        int w = videoArea.getWidth();
        int h = videoArea.getHeight();
        if (w <= 0 || h <= 0 || (w == lastW && h == lastH)) return;
        lastW = w;
        lastH = h;

        curSize = Math.min(Math.round(h * 0.92f), Math.round(w * 0.40f));
        sideSize = Math.round(curSize * 0.78f);
        float dist = curSize / 2f + sideSize / 2f + 14 * u;

        int[] sizes = {sideSize, sideSize, curSize};
        float[] xs = {-dist, dist, 0f};
        for (int k = 0; k < 3; k++) {
            ViewGroup.LayoutParams lp = cv[k].getLayoutParams();
            lp.width = sizes[k];
            lp.height = sizes[k];
            cv[k].setLayoutParams(lp);
            baseX[k] = xs[k];
            cv[k].setTranslationX(xs[k]);
            cv[k].setAlpha(k == 2 ? 1f : 0.65f);
        }
        coverInit = new boolean[3];
    }

    private void applyCover(int k, Bitmap b) {
        if (coverInit[k] && shown[k] == b) return;
        coverInit[k] = true;
        shown[k] = b;
        ImageView v = cv[k];
        int size = k == 2 ? curSize : sideSize;
        if (b != null) {
            int stroke = k == 2 ? Math.round(3 * u) : 0;
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setPadding(stroke, stroke, stroke, stroke);
            v.setColorFilter(0);
            v.setImageBitmap(b);
        } else {
            int p = size / 4;
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            v.setPadding(p, p, p, p);
            v.setColorFilter(MUTED);
            v.setImageResource(R.drawable.ic_music);
        }
    }

    private void updateCovers() {
        List<Track> q = PlayerState.queue;
        int ci = PlayerState.index;
        int pi = PlayerState.prevIndex;
        int ni = PlayerState.nextIndex;
        boolean active = PlayerState.active;

        boolean hasPrev = active && q != null && pi >= 0 && pi < q.size() && pi != ci;
        boolean hasNext = active && q != null && ni >= 0 && ni < q.size() && ni != ci;

        applyCover(2, active ? PlayerState.art : null);
        applyCover(0, hasPrev ? artCache.get(q.get(pi).path) : null);
        applyCover(1, hasNext ? artCache.get(q.get(ni).path) : null);
        cv[0].setVisibility(hasPrev ? View.VISIBLE : View.INVISIBLE);
        cv[1].setVisibility(hasNext ? View.VISIBLE : View.INVISIBLE);
    }

    /** Короткая анимация: новые обложки «подъезжают» с той стороны, куда листали. */
    private void slide(int dir) {
        float dist = curSize * 0.6f;
        for (int k = 0; k < 3; k++) {
            cv[k].setTranslationX(baseX[k] + dir * dist);
            cv[k].animate().translationX(baseX[k]).setDuration(200).start();
        }
    }

    // ---------- обновление экрана ----------

    private static String fmt(int ms) {
        int s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private void updatePanel() {
        boolean active = PlayerState.active;
        boolean vid = active && PlayerState.video;

        npTitle.setText(active ? PlayerState.title : getString(R.string.pl_idle));
        npArtist.setText(active ? PlayerState.artist : "");

        surface.setVisibility(vid ? View.VISIBLE : View.GONE);
        int coverVis = vid ? View.GONE : View.VISIBLE;
        if (!vid) {
            layoutCovers();
            updateCovers();
        } else {
            cv[0].setVisibility(coverVis);
            cv[1].setVisibility(coverVis);
        }
        cv[2].setVisibility(coverVis);

        if (PlayerState.index != lastIndex) {
            int dir = 0;
            if (lastIndex >= 0 && PlayerState.index == lastNext) {
                dir = 1;
            } else if (lastIndex >= 0 && PlayerState.index == lastPrev) {
                dir = -1;
            }
            lastIndex = PlayerState.index;
            if (dir != 0 && !vid && curSize > 0) slide(dir);
        }
        lastNext = PlayerState.nextIndex;
        lastPrev = PlayerState.prevIndex;

        if (!lastPath.equals(PlayerState.currentPath)) {
            lastPath = PlayerState.currentPath;
            adapter.notifyDataSetChanged();
        }

        if (vid && PlayerState.videoWidth > 0 && PlayerState.videoHeight > 0) {
            int cw = videoArea.getWidth();
            int ch = videoArea.getHeight();
            if (cw > 0 && ch > 0) {
                float scale = Math.min((float) cw / PlayerState.videoWidth,
                        (float) ch / PlayerState.videoHeight);
                int w = Math.round(PlayerState.videoWidth * scale);
                int h = Math.round(PlayerState.videoHeight * scale);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
                if (lp.width != w || lp.height != h) {
                    lp.width = w;
                    lp.height = h;
                    lp.gravity = Gravity.CENTER;
                    surface.setLayoutParams(lp);
                }
            }
        }

        int duration = active ? PlayerState.durationMs : 0;
        if (wave.isDragging()) {
            tCur.setText(fmt(Math.round(wave.getProgress() * duration)));
        } else {
            int pos = active ? PlayerControl.position() : 0;
            wave.setProgress(duration > 0 ? (float) pos / duration : 0f);
            tCur.setText(fmt(pos));
        }
        tTotal.setText(fmt(duration));

        int playing = PlayerState.playing ? 1 : 0;
        if (playing != shownPlaying) {
            shownPlaying = playing;
            bPlay.setImageResource(playing == 1 ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        if (PlayerState.repeat != shownRepeat) {
            shownRepeat = PlayerState.repeat;
            bRepeat.setImageResource(shownRepeat == PlayerState.REPEAT_ONE
                    ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
            bRepeat.setColorFilter(shownRepeat == PlayerState.REPEAT_OFF ? LIGHT : ACCENT);
        }
        bShuffle.setColorFilter(PlayerState.shuffle ? ACCENT : LIGHT);

        if (vid && PlayerState.playing) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        String err = PlayerState.error;
        if (err != null) {
            PlayerState.error = null;
            Toast.makeText(this, getString(R.string.pl_error, err), Toast.LENGTH_LONG).show();
        }
    }
}

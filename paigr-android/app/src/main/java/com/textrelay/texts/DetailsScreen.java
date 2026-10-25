package com.textrelay.texts;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A conversation's photos and videos, files and links, newest first (its whole history is loaded for this). */
final class DetailsScreen extends Screen {
    private final LinearLayout top;
    private final Ui.Keycap key;
    private final TextView name;
    private final TextView sub;
    private final Ui.Segmented tabs;
    private final TextView status;
    private final GridView grid;
    private final ListView files;
    private final ListView links;
    private final TextView none;
    private final Cells photos = new Cells();
    private final Lines fileLines = new Lines();
    private final Lines linkLines = new Lines();
    private String conv;
    private String tab = "photos";

    DetailsScreen(MainActivity a) {
        super(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(ui.bg);
        top = new LinearLayout(a);
        top.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        bar.setMinimumHeight(ui.dp(56));
        bar.addView(ui.iconButton(R.drawable.ic_back, "Back", Color.WHITE, v -> a.onBackPressed()));
        key = new Ui.Keycap(ui);
        key.setGlass(true);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34));
        klp.setMarginStart(ui.dp(4));
        bar.addView(key, klp);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        name = ui.line("", 17f, Ui.BLACK, Color.WHITE);
        texts.addView(name);
        sub = ui.line("", 12.5f, Ui.BOLD, 0xCCFFFFFF);
        texts.addView(sub);
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(bar);
        col.addView(top);

        tabs = ui.segmented(new String[] { "Photos", "Files", "Links" }, new String[] { "photos", "files", "links" }, this::select);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(8));
        col.addView(tabs.view, tlp);
        status = ui.label("", 13f, Ui.MEDIUM, ui.text3);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, ui.dp(4), 0, ui.dp(4));
        col.addView(status);

        FrameLayout body = new FrameLayout(a);
        grid = new GridView(a);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(ui.dp(3));
        grid.setVerticalSpacing(ui.dp(3));
        grid.setPadding(ui.dp(3), 0, ui.dp(3), ui.dp(12));
        grid.setClipToPadding(false);
        grid.setSelector(ui.pressable(4));
        grid.setDrawSelectorOnTop(true);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setAdapter(photos);
        grid.setOnItemClickListener((p, v, pos, id) -> a.openViewer(photos.items.get(pos)));
        body.addView(grid);
        files = list(fileLines);
        files.setOnItemClickListener((p, v, pos, id) -> a.openViewer(fileLines.items.get(pos).m));
        body.addView(files);
        links = list(linkLines);
        links.setOnItemClickListener((p, v, pos, id) -> {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(linkLines.items.get(pos).href)));
            } catch (android.content.ActivityNotFoundException e) {
                a.say("Nothing on this phone can open that link.", 2500);
            }
        });
        body.addView(links);
        none = ui.label("", 15f, Ui.MEDIUM, ui.text2);
        none.setGravity(Gravity.CENTER);
        none.setPadding(ui.dp(24), ui.dp(48), ui.dp(24), 0);
        body.addView(none, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root = col;
    }

    private ListView list(BaseAdapter adapter) {
        ListView l = new ListView(a);
        l.setDivider(null);
        l.setSelector(ui.pressable(0));
        l.setAdapter(adapter);
        l.setClipToPadding(false);
        return l;
    }

    @Override
    void insets(int top, int bottom, boolean keyboard) {
        this.top.setPadding(0, top, 0, 0);
        grid.setPadding(ui.dp(3), 0, ui.dp(3), bottom + ui.dp(12));
        files.setPadding(0, 0, 0, bottom + ui.dp(12));
        links.setPadding(0, 0, 0, bottom + ui.dp(12));
    }

    @Override
    boolean coloredTop() {
        return true;
    }

    void open(String c) {
        conv = c;
        Ui.Hue h = Ui.hueFor(c);
        Model.Conv cv = inbox.convs.get(c);
        key.set(h, cv != null ? cv.name : "", cv != null && cv.group);
        name.setText(cv != null ? cv.name : "Conversation");
        sub.setText(cv != null && cv.group ? "Group conversation" : "Photos, files and links");
        top.setBackground(ui.gradient(h.base, h.partner, GradientDrawable.Orientation.TL_BR, 0));
        select(tab);
        fill();
        if (!inbox.complete.contains(c)) {
            status.setText("Loading the whole conversation…");
            status.setVisibility(View.VISIBLE);
            inbox.loadWhole(c, () -> {
                if (!c.equals(conv)) return;
                status.setVisibility(View.GONE);
                fill();
            });
        } else {
            status.setVisibility(View.GONE);
        }
    }

    @Override
    void shown() {
        inbox.view = "details";
        (tab.equals("photos") ? grid : tab.equals("files") ? files : links).requestFocus();
    }

    private void select(String t) {
        tab = t;
        ui.select(tabs, t);
        grid.setVisibility("photos".equals(t) ? View.VISIBLE : View.GONE);
        files.setVisibility("files".equals(t) ? View.VISIBLE : View.GONE);
        links.setVisibility("links".equals(t) ? View.VISIBLE : View.GONE);
        empty();
    }

    private void empty() {
        int n = "photos".equals(tab) ? photos.items.size() : "files".equals(tab) ? fileLines.items.size() : linkLines.items.size();
        none.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        none.setText("photos".equals(tab) ? "No photos or videos yet." : "files".equals(tab) ? "No files yet." : "No links yet.");
    }

    private static final Pattern URL = Pattern.compile("\\bhttps?://[^\\s<>\"']+|\\bwww\\.[^\\s<>\"']+\\.[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

    private void fill() {
        List<Model.Msg> msgs = new ArrayList<>(inbox.thread(conv));
        java.util.Collections.reverse(msgs);   // newest first
        photos.items.clear();
        fileLines.items.clear();
        linkLines.items.clear();
        for (Model.Msg m : msgs) {
            Model.Media media = m.media;
            String when = Format.day(m.ts) + (m.out() ? ", you" : m.sender.isEmpty() ? "" : ", " + m.sender);
            if (media != null && (media.path != null || media.preview != null)) {
                String kind = media.kind();
                if ("Picture".equals(kind) || "Video".equals(kind)) photos.items.add(m);
                else fileLines.items.add(new Line(m, R.drawable.ic_file, kind + (media.bytes > 0 ? ", " + Format.bytes(media.bytes) : ""), when, null));
            }
            if (!m.unchecked) {
                Matcher u = URL.matcher(m.text);
                while (u.find()) {
                    String url = u.group().replaceAll("[.,!?;:'\")\\]]+$", "");
                    String href = url.toLowerCase(java.util.Locale.ROOT).startsWith("http") ? url : "https://" + url;
                    linkLines.items.add(new Line(m, R.drawable.ic_link, url, when, href));
                }
            }
        }
        tabs.options[0].setText("Photos" + (photos.items.isEmpty() ? "" : " " + photos.items.size()));
        tabs.options[1].setText("Files" + (fileLines.items.isEmpty() ? "" : " " + fileLines.items.size()));
        tabs.options[2].setText("Links" + (linkLines.items.isEmpty() ? "" : " " + linkLines.items.size()));
        photos.notifyDataSetChanged();
        fileLines.notifyDataSetChanged();
        linkLines.notifyDataSetChanged();
        empty();
    }

    private final class Cells extends BaseAdapter {
        final List<Model.Msg> items = new ArrayList<>();

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int p) {
            return items.get(p);
        }

        @Override
        public long getItemId(int p) {
            return items.get(p).k.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Cell c = convertView instanceof Cell ? (Cell) convertView : new Cell(parent.getContext());
            c.bind(items.get(position), parent.getWidth() / 3);
            return c;
        }
    }

    private final class Cell extends FrameLayout {
        final ImageView img;
        final ImageView play;
        String showing;

        Cell(Context c) {
            super(c);
            img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(img, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            play = ui.icon(R.drawable.ic_play, Color.WHITE, 30);
            addView(play, new LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER));
            setBackgroundColor(ui.surface);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);   // square
        }

        void bind(Model.Msg m, int size) {
            Model.Media media = m.media;
            play.setVisibility("Video".equals(media.kind()) ? VISIBLE : GONE);
            String p = media.preview != null ? media.preview : "Picture".equals(media.kind()) ? media.path : null;
            showing = p;
            int w = Math.max(ui.dp(80), size);
            Bitmap b = p == null ? null : inbox.media.cached(p, w);
            img.setImageBitmap(b);
            if (p != null && b == null) inbox.media.picture(p, w, got -> {
                if (p.equals(showing) && got != null) img.setImageBitmap(got);
            });
            setContentDescription(media.kind() + ", " + Format.day(m.ts));
        }
    }

    private static final class Line {
        final Model.Msg m;
        final int icon;
        final String one;
        final String two;
        final String href;

        Line(Model.Msg m, int icon, String one, String two, String href) {
            this.m = m;
            this.icon = icon;
            this.one = one;
            this.two = two;
            this.href = href;
        }
    }

    private final class Lines extends BaseAdapter {
        final List<Line> items = new ArrayList<>();

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int p) {
            return items.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Line l = items.get(position);
            Ui.Row r;
            if (convertView != null && convertView.getTag() instanceof Ui.Row) r = (Ui.Row) convertView.getTag();
            else {
                r = ui.row(l.icon, Ui.hueFor(conv).base, l.one, null);
                r.view.setTag(r);
            }
            r.title.setText(l.one);
            r.title.setSingleLine(true);
            r.title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            r.sub(l.two);
            return r.view;
        }
    }
}

package com.textrelay.texts;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The emoji panel: what you pick goes into the reply, or becomes a reaction. The phone's own emoji font draws them;
 * every one here is from Emoji 12 or earlier, so a phone on Android 10 has all of them.
 */
final class Emoji {
    private Emoji() {}

    static final String[] QUICK = { "👍", "❤️", "😂", "😮", "😢", "🙏" };

    static final String[] CATEGORY_ICONS = { "🕘", "😀", "👋", "❤️", "🐻", "🍕", "⚽", "🚗", "💡" };
    static final String[] CATEGORY_NAMES = { "Recent", "Smileys", "People", "Hearts", "Animals", "Food", "Activities", "Travel", "Objects" };

    static final String[][] CATEGORIES = {
            {},   // recents, from Prefs
            ("😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 "
                    + "😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 "
                    + "😈 👿 💀 💩 🤡 👹 👺 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾").split(" "),
            ("👋 🤚 🖐 ✋ 🖖 👌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦵 🦶 👂 👃 👀 👁 👅 👄 "
                    + "👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 💂 👷 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🤱 👼 🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 "
                    + "💆 💇 🚶 🧍 🧎 🏃 💃 🕺 🕴 👯 🧖 🧗 🏇 ⛷ 🏂 🏌 🏄 🚣 🏊 ⛹ 🏋 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘 🛀 🛌 👫 👬 👭 💏 💑 👪").split(" "),
            ("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉 ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ "
                    + "💯 💢 💥 💫 💦 💨 🕳 💣 💬 👁️‍🗨️ 🗨 🗯 💭 💤 ✅ ❌ ❎ ➕ ➖ ➗ ✖️ ♾ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ⚠️ 🚸 ⛔ 🚫 🚭 🔞 ☢️ ☣️ ⬆️ ↗️ ➡️ ↘️ ⬇️ ↙️ ⬅️ ↖️ ↕️ ↔️ "
                    + "🔁 🔂 🔄 🔀 ▶️ ⏩ ⏭ ⏯ ◀️ ⏪ ⏮ 🔼 ⏫ 🔽 ⏬ ⏸ ⏹ ⏺ ⏏ 🎦 🔅 🔆 📶 📳 📴 ♀️ ♂️ ✔️ ☑️ 🔘 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 ⭐ 🌟 ✨ ⚡ 🔥 🌈").split(" "),
            ("🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🐣 🐥 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🦟 🦗 🕷 🕸 🦂 🐢 🐍 🦎 🦖 🦕 "
                    + "🐙 🦑 🦐 🦞 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🦧 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🦮 🐕‍🦺 🐈 🐓 🦃 🦚 🦜 🦢 🦩 🕊 🐇 🦝 🦨 🦡 🦦 🦥 🐁 🐀 🐿 🦔 "
                    + "🐾 🐉 🐲 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🎍 🎋 🍃 🍂 🍁 🍄 🐚 🌾 💐 🌷 🌹 🥀 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 🪐 💫 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌪 🌈 ☀️ 🌤 ⛅ 🌥 ☁️ 🌦 🌧 ⛈ 🌩 🌨 ❄️ ☃️ ⛄ 🌬 💨 💧 💦 ☔ ☂️ 🌊 🌫").split(" "),
            ("🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🦴 🌭 🍔 🍟 🍕 "
                    + "🥪 🥙 🧆 🌮 🌯 🥗 🥘 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 ☕ 🍵 🧃 🥤 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊 🥄 🍴 🍽 🥣 🥡 🥢 🧂").split(" "),
            ("⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛷 ⛸ 🥌 🎿 ⛷ 🏂 🪂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚵 🚴 "
                    + "🏆 🥇 🥈 🥉 🏅 🎖 🏵 🎗 🎫 🎟 🎪 🤹 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🪕 🎻 🎲 ♟ 🎯 🎳 🎮 🎰 🧩").split(" "),
            ("🚗 🚕 🚙 🚌 🚎 🏎 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🦯 🦽 🦼 🛴 🚲 🛵 🏍 🛺 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩 💺 🛰 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥 🛳 ⛴ 🚢 "
                    + "⚓ ⛽ 🚧 🚦 🚥 🚏 🗺 🗿 🗽 🗼 🏰 🏯 🏟 🎡 🎢 🎠 ⛲ ⛱ 🏖 🏝 🏜 🌋 ⛰ 🏔 🗻 🏕 ⛺ 🏠 🏡 🏘 🏚 🏗 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛 ⛪ 🕌 🕍 🛕 🕋 ⛩ 🛤 🛣 🗾 🎑 🏞 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙 🌃 🌌 🌉 🌁").split(" "),
            ("⌚ 📱 📲 💻 ⌨️ 🖥 🖨 🖱 🖲 🕹 🗜 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽 🎞 📞 ☎️ 📟 📠 📺 📻 🎙 🎚 🎛 🧭 ⏱ ⏲ ⏰ 🕰 ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯 🪔 🧯 🛢 💸 💵 💴 💶 💷 💰 💳 💎 ⚖️ 🧰 🔧 🔨 ⚒ 🛠 ⛏ 🔩 ⚙️ 🧱 ⛓ 🧲 🔫 💣 🧨 🪓 🔪 🗡 ⚔️ 🛡 🚬 ⚰️ ⚱️ 🏺 🔮 📿 🧿 💈 ⚗️ 🔭 🔬 🕳 🩹 🩺 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡 🧹 🧺 🧻 🚽 🚰 🚿 🛁 🛀 🧼 🪒 🧽 🧴 🛎 🔑 🗝 🚪 🪑 🛋 🛏 🛌 🧸 🖼 🛍 🛒 🎁 🎈 🎏 🎀 🎊 🎉 🎎 🏮 🎐 🧧 ✉️ 📩 📨 📧 💌 📥 📤 📦 🏷 📪 📫 📬 📭 📮 📯 📜 📃 📄 📑 🧾 📊 📈 📉 🗒 🗓 📆 📅 🗑 📇 🗃 🗳 🗄 📋 📁 📂 🗂 🗞 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷 🔗 📎 🖇 📐 📏 🧮 📌 📍 ✂️ 🖊 🖋 ✒️ 🖌 🖍 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓").split(" ")
    };

    /** Something to do with the emoji picked. */
    interface Picked {
        void picked(String emoji);
    }

    /** The panel: a row of categories, and a grid of what's in the one chosen. */
    static final class Panel extends LinearLayout {
        private final Ui ui;
        private final Prefs prefs;
        private final GridView grid;
        private final TextView[] tabs = new TextView[CATEGORIES.length];
        private final Adapter adapter = new Adapter();
        private int category = 1;
        Picked onPick;

        Panel(Context c, Ui ui, Prefs prefs) {
            super(c);
            this.ui = ui;
            this.prefs = prefs;
            setOrientation(VERTICAL);
            setBackgroundColor(ui.dark ? ui.surface : ui.surface2);
            HorizontalScrollView strip = new HorizontalScrollView(c);
            strip.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(c);
            row.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), 0);
            for (int i = 0; i < CATEGORIES.length; i++) {
                TextView t = new TextView(c);
                t.setText(CATEGORY_ICONS[i]);
                t.setTextSize(18f);
                t.setGravity(Gravity.CENTER);
                t.setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5));
                t.setContentDescription(CATEGORY_NAMES[i]);
                t.setFocusable(true);
                t.setClickable(true);
                t.setBackground(ui.pressable(10));
                int which = i;
                t.setOnClickListener(v -> show(which));
                row.addView(t);
                tabs[i] = t;
            }
            strip.addView(row);
            addView(strip);
            grid = new GridView(c);
            grid.setNumColumns(GridView.AUTO_FIT);
            grid.setColumnWidth(ui.dp(40));
            grid.setStretchMode(GridView.STRETCH_SPACING_UNIFORM);
            grid.setVerticalScrollBarEnabled(false);
            grid.setScrollingCacheEnabled(false);
            grid.setSelector(ui.pressable(8));
            grid.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(6));
            grid.setClipToPadding(false);
            grid.setAdapter(adapter);
            grid.setOnItemClickListener((p, v, pos, id) -> pick(adapter.getItem(pos)));
            addView(grid, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            show(prefs.recentEmoji().isEmpty() ? 1 : 0);
        }

        void show(int which) {
            category = which;
            for (int i = 0; i < tabs.length; i++) tabs[i].setAlpha(i == which ? 1f : 0.45f);
            List<String> items = which == 0 ? prefs.recentEmoji() : Arrays.asList(CATEGORIES[which]);
            adapter.set(items);
            grid.setSelection(0);
        }

        private void pick(String e) {
            if (e == null) return;
            prefs.rememberEmoji(e);
            if (category == 0) adapter.set(prefs.recentEmoji());
            if (onPick != null) onPick.picked(e);
        }

        /** Moves the keyboard focus into the grid, for a phone with arrows and OK. */
        void focusGrid() {
            grid.requestFocus();
            if (grid.getCount() > 0) grid.setSelection(0);
        }

        private final class Adapter extends BaseAdapter {
            private List<String> items = new ArrayList<>();

            void set(List<String> next) {
                items = new ArrayList<>(next);
                notifyDataSetChanged();
            }

            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public String getItem(int position) {
                return position >= 0 && position < items.size() ? items.get(position) : null;
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = convertView instanceof TextView ? (TextView) convertView : null;
                if (t == null) {
                    t = new TextView(parent.getContext());
                    t.setTextSize(24f);
                    t.setGravity(Gravity.CENTER);
                    t.setHeight(ui.dp(40));
                    t.setIncludeFontPadding(false);
                }
                t.setText(items.get(position));
                return t;
            }
        }
    }
}

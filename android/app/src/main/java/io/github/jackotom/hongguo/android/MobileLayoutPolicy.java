package io.github.jackotom.hongguo.android;

/** Phone geometry only. Values are dp; media, preferences and TV navigation stay untouched. */
final class MobileLayoutPolicy {
    private MobileLayoutPolicy() { }

    static int webBottomInset(boolean television, int systemBottom, int imeBottom) {
        // IME insets already include the navigation area. Adding the two creates a second gap.
        return Math.max(0, television ? systemBottom : Math.max(systemBottom, imeBottom));
    }

    static final class Layout {
        final boolean compact;
        final int columns, optionColumns, buttonHeight, backWidth, padding, titleSize, statusSize;
        Layout(boolean compact, int columns, int optionColumns, int buttonHeight, int backWidth) {
            this.compact = compact; this.columns = columns; this.optionColumns = optionColumns;
            this.buttonHeight = buttonHeight; this.backWidth = backWidth;
            padding = compact ? 4 : 8; titleSize = compact ? 15 : 17; statusSize = compact ? 11 : 12;
        }

        int controlsHeight(int availableHeight, int measuredContentHeight, int topHeight) {
            // The controls scroll internally at large font sizes instead of covering the Back bar.
            int belowHeading = Math.max(48, availableHeight - Math.max(0, topHeight) - 48);
            int share = Math.max(48, (int) Math.floor(availableHeight * (compact ? .56 : .48)));
            return Math.max(48, Math.min(Math.max(48, measuredContentHeight), Math.min(belowHeading, share)));
        }
    }

    static Layout phone(int width, int height, float fontScale) {
        width = Math.max(1, width); height = Math.max(1, height);
        float scale = Float.isFinite(fontScale) && fontScale > 0 ? fontScale : 1f;
        boolean compact = height < 420 && width > height;
        int minButtonWidth = Math.max(80, (int) Math.ceil(42 * scale + 20));
        int columns = Math.max(1, Math.min(compact ? 5 : 3, width / minButtonWidth));
        int optionColumns = width >= Math.ceil(280 * scale) ? 2 : 1;
        int buttonHeight = Math.max(48, (int) Math.ceil(36 * scale + 8));
        int backWidth = Math.min(Math.max(48, width / 2), Math.max(80, (int) Math.ceil(28 * scale + 24)));
        return new Layout(compact, columns, optionColumns, buttonHeight, backWidth);
    }
}

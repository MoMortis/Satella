package greenebolt.autotrade;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

public enum TradeMode implements IConfigOptionListEntry {
    SINGLE_TRADE("single", "单次交易"),
    AUTO_TRADE("auto", "自动交易");

    private final String stringValue;
    private final String displayName;

    TradeMode(String stringValue, String displayName) {
        this.stringValue = stringValue;
        this.displayName = displayName;
    }

    @Override
    public String getStringValue() {
        return this.stringValue;
    }

    @Override
    public String getDisplayName() {
        return this.displayName;
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        int id = this.ordinal() + (forward ? 1 : -1);
        TradeMode[] values = values();
        return values[(id + values.length) % values.length];
    }

    @Override
    public IConfigOptionListEntry fromString(String str) {
        for (TradeMode mode : values()) {
            if (mode.stringValue.equalsIgnoreCase(str)) {
                return mode;
            }
        }
        return AUTO_TRADE;
    }
}

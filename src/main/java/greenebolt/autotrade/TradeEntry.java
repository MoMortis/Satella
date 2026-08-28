package greenebolt.autotrade;

import net.minecraft.item.Item;
import net.minecraft.village.TradeOffer;

import java.util.ArrayList;
import java.util.List;

public class TradeEntry {
    private final List<Item> inputs;
    private final Item output;

    private TradeEntry(List<Item> inputs, Item output) {
        this.inputs = inputs;
        this.output = output;
    }

    public static TradeEntry build(String input1, String input2, String output) {
        Item outputItem = ItemNameUtils.parseItem(output);
        if (outputItem == null) {
            return null;
        }

        List<Item> inputs = new ArrayList<>();
        Item inputItem1 = ItemNameUtils.parseItem(input1);
        if (inputItem1 == null) {
            return null;
        }
        inputs.add(inputItem1);

        if (!input2.trim().isEmpty()) {
            Item inputItem2 = ItemNameUtils.parseItem(input2);
            if (inputItem2 == null) {
                return null;
            }
            inputs.add(inputItem2);
        }

        return new TradeEntry(inputs, outputItem);
    }

    public boolean matches(TradeOffer offer) {
        if (offer.getSellItem().getItem() != this.output) {
            return false;
        }
        if (offer.getFirstBuyItem().item().value() != this.inputs.get(0)) {
            return false;
        }
        if (this.inputs.size() >= 2) {
            var secondBuy = offer.getSecondBuyItem();
            if (secondBuy.isEmpty() || secondBuy.get().item().value() != this.inputs.get(1)) {
                return false;
            }
        } else {
            // 只配置了一个输入时，不能误匹配双输入交易（例如 "纸+绿宝石" 这种）
            if (!offer.getSecondBuyItem().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}

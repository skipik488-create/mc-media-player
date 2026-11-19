package dev.skipik.mediaplayer.screen;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Generic stepped slider mapping [0..1] to [min..max]. */
public final class ValueSlider extends AbstractSliderButton {
    private final double min;
    private final double max;
    private final double step;
    private final DoubleFunction<Component> label;
    private final DoubleConsumer onChange;

    public ValueSlider(int x, int y, int width, int height, double min, double max, double step, double initial,
                       DoubleFunction<Component> label, DoubleConsumer onChange) {
        super(x, y, width, height, Component.empty(), clamp01((initial - min) / (max - min)));
        this.min = min;
        this.max = max;
        this.step = step;
        this.label = label;
        this.onChange = onChange;
        updateMessage();
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    public double current() {
        double raw = min + value * (max - min);
        return step > 0 ? Math.round(raw / step) * step : raw;
    }

    @Override
    protected void updateMessage() {
        if (label != null) setMessage(label.apply(current()));
    }

    @Override
    protected void applyValue() {
        if (onChange != null) onChange.accept(current());
    }
}

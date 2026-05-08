package dev.sorokin.screennavigator;

import javax.swing.*;
import java.awt.*;

public abstract class Screen extends JPanel {

    public Screen(LayoutManager layout) {
        super(layout);
    }

    public void onShow() {}

    public void onHide() {}
}

package org.apache.pdfbox.text;

import java.awt.*;
import java.awt.geom.Rectangle2D;

public class RectangleAndPage {
    public final Rectangle2D rectangle;
    public final int page;

    public RectangleAndPage(int page, Rectangle2D rectangle) {
        this.page = page;
        this.rectangle = rectangle;
    }
}

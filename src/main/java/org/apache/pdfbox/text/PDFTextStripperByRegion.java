package org.apache.pdfbox.text;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

public class PDFTextStripperByRegion extends PdfContentStreamEditor {
    private static final List<String> TEXT_SHOWING_OPERATORS = Arrays.asList("Tj", "'", "\"", "TJ");

    private final PDDocument document;
    private final List<Rectangle2D> regions = new ArrayList<>();

    private boolean operatorHasTextToBeRemoved = false;

    public PDFTextStripperByRegion(PDDocument document) {
        super(document);

        this.document = document;
    }

    public void addRegion(Rectangle2D rect) {
        regions.add(rect);
    }

    @Override
    protected void nextOperation(Operator operator, List<COSBase> operands) {
        operatorHasTextToBeRemoved = false;

        super.nextOperation(operator, operands);
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
            throws IOException {
        //new Exception().printStackTrace();
        super.showGlyph(textRenderingMatrix, font, code, displacement);
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        regions.forEach((rect) -> {
            if (rect.contains(text.getX(), text.getPageHeight() - text.getY())) {
                operatorHasTextToBeRemoved = true;
            }
        });

        super.processTextPosition(text);
    }

    @Override
    protected void write(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
        String operatorString = operator.getName();

        if (TEXT_SHOWING_OPERATORS.contains(operatorString) && operatorHasTextToBeRemoved) {
            return;
        }

        super.write(contentStreamWriter, operator, operands);
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        super.processPage(page);

        PDPageContentStream pageContentStream = new PDPageContentStream(this.document, page, PDPageContentStream.AppendMode.APPEND, true);
        pageContentStream.setStrokingColor(Color.RED);
        for (Rectangle2D region : regions) {
            pageContentStream.moveTo((float) region.getX(), (float) region.getY());
            pageContentStream.lineTo((float) region.getX(), (float) (region.getY() + region.getHeight()));
            pageContentStream.lineTo((float) (region.getX() + region.getWidth()), (float) (region.getY() + region.getHeight()));
            pageContentStream.lineTo((float) (region.getX() + region.getWidth()), (float) region.getY());
            pageContentStream.lineTo((float) region.getX(), (float) region.getY());
        }
        pageContentStream.stroke();
        pageContentStream.close();
    }

    public static void main(String[] args) throws IOException {
        PDDocument document = Loader.loadPDF(new File("pdfSweep-whitepaper.pdf"));

        PDFTextStripperByRegion stripper = new PDFTextStripperByRegion(document);
        stripper.addRegion(new Rectangle2D.Float(100, 100, 200, 200));
        stripper.getText(document);

        document.save(new File("pdfSweep-whitepaper-redacted.pdf"));
    }
}

package org.apache.pdfbox.text;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

public class PDFTextStripperByRegion extends PdfContentStreamEditor {
    private static final List<String> TEXT_SHOWING_OPERATORS = Arrays.asList("Tj", "'", "\"", "TJ");

    public static final boolean DEBUG = true;

    private final PDDocument document;
    private final List<Rectangle2D> regions = new ArrayList<>();

    private final List<TextPosition> operatorText = new ArrayList<>();

    public PDFTextStripperByRegion(PDDocument document) {
        super(document);

        this.document = document;
    }

    public void addRegion(Rectangle2D rect) {
        regions.add(rect);
    }

    protected boolean matchesRegion(TextPosition text) {
        for (Rectangle2D rect : regions) {
            if (rect.contains(text.getX(), text.getPageHeight() - text.getY())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void nextOperation(Operator operator, List<COSBase> operands) {
        operatorText.clear();

        super.nextOperation(operator, operands);
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        operatorText.add(text);

        super.processTextPosition(text);
    }

    @Override
    protected void write(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
        String operatorString = operator.getName();

        if (TEXT_SHOWING_OPERATORS.contains(operatorString)) {
            boolean operatorHasTextToBeRemoved = false;
            boolean operatorHasTextToBeKept = false;

            for (TextPosition text : operatorText) {
                boolean textToBeRemoved = false;

                for (Rectangle2D rect : regions) {
                    if (rect.contains(text.getX(), text.getPageHeight() - text.getY())) {
                        textToBeRemoved = true;
                    }
                };

                operatorHasTextToBeRemoved |= textToBeRemoved;
                operatorHasTextToBeKept |= !textToBeRemoved;
            };

            if (operatorHasTextToBeRemoved) {
                if (!operatorHasTextToBeKept) {
                    // Remove at all
                    return;
                } else {
                    if (OperatorName.SHOW_TEXT.equals(operator.getName())) {
                        System.err.println(operatorString);
                        System.err.println(operands);
                        patchShowTextOperation(contentStreamWriter, operatorText);
                        return;
                    } else {
                        // Remove at all for now
                        // TODO: fix me
                        return;
                    }
                }
            }
        }

        super.write(contentStreamWriter, operator, operands);
    }

    protected void patchShowTextOperation(ContentStreamWriter contentStreamWriter, List<TextPosition> operatorText) throws IOException {
        // TODO: offset
        for (int i = 0; i < operatorText.size(); ) {
            if (!matchesRegion(operatorText.get(i))) {
                int to = i;
                ByteArrayOutputStream textRange = new ByteArrayOutputStream();
                while (to < operatorText.size() && !matchesRegion(operatorText.get(to))) {
                    // TODO: multi-byte fonts
                    textRange.write(operatorText.get(to).getCharacterCodes()[0]);
                    to++;
                }

                //System.err.println(i + " " + to);
                i = to;

                // TODO: offset
                List<COSBase> operands = Collections.singletonList(new COSString(textRange.toByteArray()));
                System.err.println("New: " + operands);
                super.write(contentStreamWriter, Operator.getOperator(OperatorName.SHOW_TEXT), operands);
            } else {
                i++;
            }
        }
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        super.processPage(page);

        if (!DEBUG) {
            return;
        }

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

    // Tj - ShowText
    @Override
    protected void showText(byte[] string) throws IOException {
        super.showText(string);
    }

    public static void main(String[] args) throws IOException {
        PDDocument document = Loader.loadPDF(new File("pdfSweep-whitepaper.pdf"));

        PDFTextStripperByRegion stripper = new PDFTextStripperByRegion(document);
        stripper.addRegion(new Rectangle2D.Float(100, 100, 200, 200));
        stripper.getText(document);

        document.save(new File("pdfSweep-whitepaper-redacted.pdf"));
    }
}

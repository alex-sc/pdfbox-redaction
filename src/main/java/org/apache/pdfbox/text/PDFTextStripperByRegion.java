package org.apache.pdfbox.text;

import java.awt.*;
import java.awt.geom.AffineTransform;
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
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

public class PDFTextStripperByRegion extends PdfContentStreamEditor {
    private static final List<String> TEXT_SHOWING_OPERATORS = Arrays.asList("Tj", "'", "\"", "TJ");

    public static final boolean DEBUG = true;

    private final PDDocument document;
    private final List<RectangleAndPage> regions = new ArrayList<>();

    private final List<TextPosition> operatorText = new ArrayList<>();
    private final List<RectangleAndPage> imageLocations = new ArrayList<>();

    public PDFTextStripperByRegion(PDDocument document) {
        super(document);

        this.document = document;

        addOperator(new DrawObjectExt(this));
    }

    public void addRegion(int page, Rectangle2D rect) {
        regions.add(new RectangleAndPage(page, rect));
    }

    protected boolean matchesRegion(TextPosition text) {
        for (RectangleAndPage location : regions) {
            if (location.page != getCurrentPageNo()) {
                continue;
            }

            Rectangle2D rect = location.rectangle;
            if (rect.contains(text.getX(), text.getPageHeight() - text.getY())) {
                return true;
            }
            if (rect.contains(text.getX() + text.getWidth(), text.getPageHeight() - text.getY())) {
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
                boolean textToBeRemoved = matchesRegion(text);
                operatorHasTextToBeRemoved |= textToBeRemoved;
                operatorHasTextToBeKept |= !textToBeRemoved;
            }

            if (operatorHasTextToBeRemoved) {
                if (!operatorHasTextToBeKept) {
                    // Remove at all
                    return;
                } else {
                    if (OperatorName.SHOW_TEXT.equals(operator.getName())) {
                        patchShowTextOperation(contentStreamWriter, operatorText, operands);
                        return;
                    } else if (OperatorName.SHOW_TEXT_ADJUSTED.equals(operator.getName())) {
                        patchShowTextAdjustedOperation(contentStreamWriter, operatorText, operands);
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

    protected void patchShowTextAdjustedOperation(ContentStreamWriter contentStreamWriter, List<TextPosition> operatorText, List<COSBase> operands) throws IOException {
        List<COSBase> newOperandsArray = new ArrayList<>();

        List<TextPosition> texts = new ArrayList<>(operatorText);
        COSArray operandsArray = (COSArray) operands.get(0);
        int textIndex = 0;
        float offset = 0.0f;
        for (COSBase operand: operandsArray.toList()) {
            if (operand instanceof COSNumber) {
                offset += ((COSNumber) operand).floatValue();
            } else if (operand instanceof COSString) {
                byte[] textBytes = ((COSString) operand).getBytes();
                // TODO: multibyte
                int numberOfCharacters = textBytes.length;

                int from = 0;
                while (from < numberOfCharacters) {
                    TextPosition text = texts.get(textIndex);
                    if (matchesRegion(text)) {
                        // TODO: correct?
                        offset -= (text.getWidth()) * 100f;
                        from++;
                        textIndex++;
                    } else {
                        if (offset != 0) {
                            newOperandsArray.add(new COSFloat(offset));
                            offset = 0;
                        }

                        ByteArrayOutputStream textRange = new ByteArrayOutputStream();
                        int to = from;
                        while (to < numberOfCharacters && !matchesRegion(texts.get(textIndex))) {
                            // TODO: multi-byte fonts
                            textRange.write(operatorText.get(textIndex).getCharacterCodes()[0]);
                            to++;
                            textIndex++;
                        }

                        newOperandsArray.add(new COSString(textRange.toByteArray()));

                        from = to;
                    }
                }
            }
        }

        //System.err.println("Old: " + operands);
        List<COSBase> newOperands = Collections.singletonList(new COSArray(newOperandsArray));
        //System.err.println("New: " + newOperands);
        super.write(contentStreamWriter, Operator.getOperator(OperatorName.SHOW_TEXT_ADJUSTED), newOperands);
    }

    protected void patchShowTextOperation(ContentStreamWriter contentStreamWriter, List<TextPosition> operatorText, List<COSBase> operands) throws IOException {
        List<COSBase> newOperands = Collections.singletonList(new COSArray(operands));
        patchShowTextAdjustedOperation(contentStreamWriter, operatorText, newOperands);
    }

    // See org.apache.pdfbox.rendering.PageDrawer#drawImage
    public void drawImage(PDImageXObject xObject) {
        System.err.println(xObject.getClass());
        System.err.println(xObject);

        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        AffineTransform at = ctm.createAffineTransform();

        float x = ctm.getTranslateX();
        float y = ctm.getTranslateY();

        imageLocations.add(new RectangleAndPage(getCurrentPageNo(), new Rectangle2D.Float(x, y, xObject.getWidth(), xObject.getHeight())));
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        super.processPage(page);

        if (!DEBUG) {
            return;
        }

        PDPageContentStream pageContentStream = new PDPageContentStream(this.document, page, PDPageContentStream.AppendMode.APPEND, true);
        pageContentStream.setStrokingColor(Color.RED);
        for (RectangleAndPage location : regions) {
            if (getCurrentPageNo() != location.page) {
                continue;
            }
            Rectangle2D region = location.rectangle;
            pageContentStream.moveTo((float) region.getX(), (float) region.getY());
            pageContentStream.lineTo((float) region.getX(), (float) (region.getY() + region.getHeight()));
            pageContentStream.lineTo((float) (region.getX() + region.getWidth()), (float) (region.getY() + region.getHeight()));
            pageContentStream.lineTo((float) (region.getX() + region.getWidth()), (float) region.getY());
            pageContentStream.lineTo((float) region.getX(), (float) region.getY());
        }
        pageContentStream.stroke();

        pageContentStream.setStrokingColor(Color.GREEN);
        for (RectangleAndPage imageLocation : imageLocations) {
            if (getCurrentPageNo() != imageLocation.page) {
                continue;
            }
            Rectangle2D region = imageLocation.rectangle;
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
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            stripper.addRegion(i, new Rectangle2D.Float(100, 100, 200, 200));
        }
        stripper.getText(document);

        document.save(new File("pdfSweep-whitepaper-redacted.pdf"));
    }
}

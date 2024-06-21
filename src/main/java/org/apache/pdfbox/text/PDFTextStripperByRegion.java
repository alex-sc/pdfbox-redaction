package org.apache.pdfbox.text;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
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
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;

public class PDFTextStripperByRegion extends PdfContentStreamEditor {
    private static final List<String> TEXT_SHOWING_OPERATORS = Arrays.asList("Tj", "'", "\"", "TJ");

    private static final boolean DEBUG = true;
    private static final Color IMAGE_FILL_COLOR = Color.BLUE;

    private final PDDocument document;

    // State
    private final List<RectangleAndPage> regions = new ArrayList<>();
    private final List<TextPosition> operatorText = new ArrayList<>();

    private COSName intersectingImageName = null;
    private BufferedImage intersectingImage = null;

    // Debug
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
            if (location.page != getCurrentPageNo() - 1) {
                continue;
            }

            Rectangle2D rect = location.rectangle;
            if (rect.intersects(text.getX(), text.getPageHeight() - text.getY(), text.getWidth(), text.getHeight())) {
                return true;
            }
            if (rect.contains(text.getX() + text.getWidth(), text.getPageHeight() - text.getY())) {
                return true;
            }
        }
        return false;
    }

    protected boolean matchesRegion(Rectangle2D box) {
        for (RectangleAndPage location : regions) {
            if (location.page != getCurrentPageNo() - 1) {
                continue;
            }

            Rectangle2D rect = location.rectangle;
            if (rect.intersects(box.getX(), box.getY(), box.getWidth(), box.getHeight())) {
                return true;
            }
        }
        return false;
    }

    protected void clearImage(Rectangle2D box, BufferedImage image) {
        for (RectangleAndPage location : regions) {
            if (location.page != getCurrentPageNo() - 1) {
                continue;
            }

            Rectangle2D rect = location.rectangle;
            Rectangle2D intersection = rect.createIntersection(box);
            if (intersection.getWidth() > 0 && intersection.getHeight() > 0) {
                double scaleX = box.getWidth() / image.getWidth();
                double scaleY = box.getHeight() / image.getHeight();

                double iw = intersection.getWidth() / scaleX;
                double ih = intersection.getHeight() / scaleY;
                double ix = (intersection.getX() - box.getX()) / scaleX;
                double iy = (intersection.getY() - box.getY()) / scaleX;
                // Inverse the vertical coordinate
                iy = image.getHeight() - ih - iy;

                Graphics2D graphics = image.createGraphics();
                graphics.clearRect((int) ix, (int) iy, (int) iw, (int) ih);
            }
        }
    }

    @Override
    protected void nextOperation(Operator operator, List<COSBase> operands) {
        operatorText.clear();
        intersectingImageName = null;
        intersectingImage = null;

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

        if (OperatorName.DRAW_OBJECT.equals(operator.getName())) {
            if (intersectingImageName != null) {
                patchDrawObjectImage(contentStreamWriter, operator, operands);
                return;
            }
        }

        super.write(contentStreamWriter, operator, operands);
    }

    protected void patchDrawObjectImage(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
        PDImageXObject imageXObject = (PDImageXObject) getResources().getXObject(intersectingImageName);
        BufferedImage image = imageXObject.getImage();

        // TODO: assign new key since the image might be used in several places
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        PDImageXObject newImageXObject = PDImageXObject.createFromByteArray(document, outputStream.toByteArray(), intersectingImageName.getName());
        getResources().put(intersectingImageName, newImageXObject);

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
                PDFont font = getGraphicsState().getTextState().getFont();
                InputStream in = new ByteArrayInputStream(textBytes);
                int numberOfCharacters = 0;
                while (in.available() > 0) {
                    font.readCode(in);
                    numberOfCharacters++;
                }
                int bytesPerCharacter = textBytes.length / numberOfCharacters;

                int from = 0;
                while (from < numberOfCharacters) {
                    TextPosition text = texts.get(textIndex);
                    if (matchesRegion(text)) {
                        // TODO: correct?
                        offset -= text.getWidth() / text.getTextMatrix().getScalingFactorX() * 1000f;
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
                            int characterCode = operatorText.get(textIndex).getCharacterCodes()[0];
                            byte[] charBytes = new byte[bytesPerCharacter];
                            for (int i = 0; i < bytesPerCharacter; i++) {
                                charBytes[bytesPerCharacter - 1 - i] = (byte) ((characterCode % 256) & 0xff);
                                characterCode /= 256;
                            }
                            textRange.write(charBytes);
                            to++;
                            textIndex++;
                        }

                        newOperandsArray.add(new COSString(textRange.toByteArray()));

                        from = to;
                    }
                }
            }
        }

        List<COSBase> newOperands = Collections.singletonList(new COSArray(newOperandsArray));
        super.write(contentStreamWriter, Operator.getOperator(OperatorName.SHOW_TEXT_ADJUSTED), newOperands);
    }

    protected void patchShowTextOperation(ContentStreamWriter contentStreamWriter, List<TextPosition> operatorText, List<COSBase> operands) throws IOException {
        List<COSBase> newOperands = Collections.singletonList(new COSArray(operands));
        patchShowTextAdjustedOperation(contentStreamWriter, operatorText, newOperands);
    }

    // See org.apache.pdfbox.rendering.PageDrawer#drawImage
    public void drawImage(PDImageXObject xObject, COSName name) throws IOException {
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();

        float x = ctm.getTranslateX();
        float y = ctm.getTranslateY();

        float scaleX = ctm.getScaleX();
        float scaleY = ctm.getScaleY();

        Rectangle2D imageLocation = new Rectangle2D.Float(x, y, scaleX, scaleY);
        if (matchesRegion(imageLocation)) {
            BufferedImage image = xObject.getImage();
            intersectingImageName = name;
            intersectingImage = image.getSubimage(0, 0, image.getWidth(), image.getHeight());
            clearImage(imageLocation, intersectingImage);
        }
        imageLocations.add(new RectangleAndPage(getCurrentPageNo() - 1, imageLocation));
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        super.processPage(page);

        if (!DEBUG) {
            return;
        }

        PDPageContentStream pageContentStream = new PDPageContentStream(this.document, page, PDPageContentStream.AppendMode.APPEND, true, true);
        pageContentStream.setStrokingColor(Color.RED);
        for (RectangleAndPage location : regions) {
            if (getCurrentPageNo() - 1 != location.page) {
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
            if (getCurrentPageNo() - 1 != imageLocation.page) {
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
        PDDocument document = Loader.loadPDF(new File("5000 most common chinese characters.pdf"));

        PDFTextStripperByRegion stripper = new PDFTextStripperByRegion(document);
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            PDPage page = document.getPage(i);
            stripper.addRegion(i, new Rectangle2D.Float(100, 100, page.getMediaBox().getWidth() - 400, page.getMediaBox().getHeight() - 400));
        }
        stripper.getText(document);

        document.save(new File("5000 most common chinese characters-redacted.pdf"));
    }
}

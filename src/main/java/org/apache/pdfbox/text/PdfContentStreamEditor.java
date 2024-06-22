package org.apache.pdfbox.text;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.*;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <a href="https://stackoverflow.com/questions/58475104/filter-out-all-text-above-a-certain-font-size-from-pdf">
 * Filter out all text above a certain font size from PDF
 * </a>
 * <p>
 * This class presents a simple content stream editing framework. As is it creates an equivalent
 * copy of the original page content stream. To actually edit, simply overwrite the method
 * {@link #write(ContentStreamWriter, Operator, List)} to not (as in this class) write
 * the given operations as they are but change them in some fancy way.
 * </p>
 * <p>
 * This is a port of the iText 5 test area class <code>PdfContentStreamEditor</code>
 * and the iText 7 test area class <code>PdfCanvasEditor</code>.
 * </p>
 *
 * @author mkl
 */
public class PdfContentStreamEditor extends PDFTextStripper {

    private final PDDocument document;

    private final Stack<ContentStreamWriter> replacement = new Stack<>();
    private final Stack<AtomicBoolean> inOperator = new Stack<>();

    public PdfContentStreamEditor(PDDocument document) {
        this.document = document;
        inOperator.add(new AtomicBoolean(false));
    }

    /**
     * <p>
     * This method retrieves the next operation before its registered
     * listener is called. The default does nothing.
     * </p>
     * <p>
     * Override this method to retrieve state information from before the
     * operation execution.
     * </p>
     */
    protected void nextOperation(Operator operator, List<COSBase> operands) {
        // Do nothing
    }

    /**
     * <p>
     * This method writes content stream operations to the target canvas. The default
     * implementation writes them as they come, so it essentially generates identical
     * copies of the original instructions {@link #processOperator(Operator, List)}
     * forwards to it.
     * </p>
     * <p>
     * Override this method to achieve some fancy editing effect.
     * </p>
     */
    protected void write(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
        contentStreamWriter.writeTokens(operands);
        contentStreamWriter.writeToken(operator);
    }

    // Actual editing methods
    @Override
    public void processPage(PDPage page) throws IOException {
        PDStream stream = new PDStream(document);
        OutputStream replacementStream = stream.createOutputStream(COSName.FLATE_DECODE);
        replacement.push(new ContentStreamWriter(replacementStream));

        super.processPage(page);
        page.setContents(stream);

        replacementStream.close();
        replacement.pop();
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        ByteArrayOutputStream replacementStream = new ByteArrayOutputStream();
        replacement.push(new ContentStreamWriter(replacementStream));
        inOperator.push(new AtomicBoolean(false));

        super.showForm(form);

        OutputStream outputStream = form.getContentStream().createOutputStream(COSName.FLATE_DECODE);
        outputStream.write(replacementStream.toByteArray());
        outputStream.close();

        replacement.pop();
        inOperator.pop();
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (inOperator.peek().get()) {
            super.processOperator(operator, operands);
        } else {
            inOperator.peek().set(true);
            nextOperation(operator, operands);
            super.processOperator(operator, operands);

            write(replacement.peek(), operator, operands);
            inOperator.peek().set(false);
        }
    }
}

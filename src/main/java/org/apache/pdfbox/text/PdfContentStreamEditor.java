package org.apache.pdfbox.text;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

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
    private ContentStreamWriter replacement = null;
    private boolean inOperator = false;

    public PdfContentStreamEditor(PDDocument document) {
        this.document = document;
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
        OutputStream replacementStream;
        replacement = new ContentStreamWriter(replacementStream = stream.createOutputStream(COSName.FLATE_DECODE));
        super.processPage(page);
        replacementStream.close();
        page.setContents(stream);
        replacement = null;
    }

    // PDFStreamEngine overrides to allow editing
    @Override
    public void showForm(PDFormXObject form) throws IOException {
        // DON'T descend into XObjects
        // super.showForm(form);
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (inOperator) {
            super.processOperator(operator, operands);
        } else {
            inOperator = true;
            nextOperation(operator, operands);
            super.processOperator(operator, operands);
            write(replacement, operator, operands);
            inOperator = false;
        }
    }
}

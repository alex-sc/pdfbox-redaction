package org.apache.pdfbox.text;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.MissingOperandException;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.util.List;

public class DrawObjectExt extends DrawObject {

    public DrawObjectExt(PDFTextStripperByRegion context) {
        super(context);
    }

    @Override
    public void process(Operator operator, List<COSBase> arguments) throws IOException {
        if (arguments.isEmpty()) {
            throw new MissingOperandException(operator, arguments);
        }
        COSBase base0 = arguments.get(0);
        if (!(base0 instanceof COSName)) {
            return;
        }
        COSName name = (COSName) base0;

        PDFStreamEngine context = getContext();
        PDXObject pdxObject = context.getResources().getXObject(name);
        if (pdxObject instanceof PDImageXObject) {
            ((PDFTextStripperByRegion) context).drawImage((PDImageXObject) pdxObject, name);
        } else {
            super.process(operator, arguments);
        }
    }
}

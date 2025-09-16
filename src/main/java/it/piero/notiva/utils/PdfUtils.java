package it.piero.notiva.utils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class PdfUtils {

    public List<byte[]> renderPdfToImages(byte[] pdfBytes, int dpi) throws IOException {

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {

            PDFRenderer renderer = new PDFRenderer(doc);
            renderer.setSubsamplingAllowed(false);

            List<byte[]> pages = new ArrayList<>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
                ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();

                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.8f);
                }

                writer.write(null, new IIOImage(img, null, null), param);
                writer.dispose();
                ios.close();

                pages.add(baos.toByteArray());
            }
            return pages;
        }
    }


}

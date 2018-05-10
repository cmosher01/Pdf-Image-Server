package nu.mine.mosher;

import fi.iki.elonen.NanoHTTPD;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Runtime.getRuntime;

class PdfImageServer {
    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        LOG = LoggerFactory.getLogger(PdfImageServer.class);
        CWD = getCwd();
    }

    private static Path getCwd() {
        try {
            return Paths.get(System.getProperty("user.dir", "./")).toAbsolutePath().normalize().toRealPath();
        } catch (final Throwable ignore) {
            LOG.error("Cannot find current default directory.", ignore);
            return null;
        }
    }

    private static final Logger LOG;
    private static final Path CWD;

    public static void main(final String... args) throws IOException {
        LOG.debug("Now serving files beneath {}", CWD.toString());

        final NanoHTTPD server = new NanoHTTPD(8080) {
            @Override
            public Response serve(final IHTTPSession session) {
                LOG.trace("--------------------------------------------------");
                try {
                    final int iPage = getPageNumber(session);
                    LOG.trace("Page number (zero-origin): {}", iPage);
                    final Path path = getPdfPath(session);
                    return newChunkedResponse(Response.Status.OK, "image/png", getImage(path, iPage));
                } catch (final Throwable ignore) {
                    LOG.error("Exception while processing request:", ignore);
                    return newFixedLengthResponse(Response.Status.UNSUPPORTED_MEDIA_TYPE, MIME_PLAINTEXT, "");
                }
            }
        };

        getRuntime().addShutdownHook(new Thread(server::stop));
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    private static int getPageNumber(final NanoHTTPD.IHTTPSession session) {
        final List<String> ps = session.getParameters().getOrDefault("page", Collections.singletonList("1"));
        if (ps.isEmpty()) {
            LOG.warn("Empty page number.");
            return 0;
        }
        try {
            return Integer.parseInt(ps.get(0)) - 1;
        } catch (final Throwable e) {
            LOG.error("Cannot parse page number: {}", ps.get(0), e);
            return 0;
        }
    }

    private static Path getPdfPath(final NanoHTTPD.IHTTPSession session) throws IOException {
        final String uri = "./" + session.getUri();
        LOG.trace("Received request for URI: {}", uri);
        final Path path = CWD.resolve(Paths.get(uri)).toAbsolutePath().normalize();
        LOG.trace("Resolved: {} to: {}", uri, path.toString());
        final Path pathReal = path.toRealPath();
        LOG.trace("File exists: {}", pathReal.toString());
        verifyNoDirectoryTraversal(pathReal);
        verifyIsFile(pathReal);
        return pathReal;
    }

    private static void verifyIsFile(final Path path) throws IOException {
        if (!(path.toFile().isFile() && Files.isReadable(path))) {
            throw new IOException("File is not a file (or is not readable.");
        }
    }

    private static void verifyNoDirectoryTraversal(final Path path) throws IOException {
        if (!(path.startsWith(CWD))) {
            throw new IOException("Detected directory-traversal attempt.");
        }
    }

    private static InputStream getImage(final Path pathPdf, final int iPage) throws IOException {
        final BufferedImage img = getImageFromPage(pathPdf, iPage);
        final ByteArrayOutputStream buf = new ByteArrayOutputStream(128*1024);
        LOG.trace("Begin generating PNG image...");
        ImageIO.write(img, "png", buf);
        buf.flush();
        buf.close();
        LOG.trace("Completed generating PNG image.");
        return new ByteArrayInputStream(buf.toByteArray());
    }

    private static BufferedImage getImageFromPage(final Path pathPdf, final int iPage) throws IOException {
        try (final PDDocument doc = PDDocument.load(pathPdf.toFile())) {
            LOG.trace("Loaded PDF document from: {}", pathPdf.toString());
            final int cPage = doc.getNumberOfPages();
            LOG.trace("Document has {} pages", cPage);
            verifyPageInRange(iPage, cPage);
            final PDPage page = doc.getPage(iPage);
            final PDResources rsrs = page.getResources();
            for (final COSName name : rsrs.getXObjectNames()) {
                final PDXObject obj = rsrs.getXObject(name);
                if (obj instanceof PDImageXObject) {
                    LOG.trace("Found PD image: {}", name.getName());

                    final PDRectangle media = page.getMediaBox();
                    LOG.trace("Page media-box: {}", media);

                    final PDRectangle art = page.getArtBox();
                    LOG.trace("Page art-box: {}", art);

                    final PDRectangle crop = page.getCropBox();
                    LOG.trace("Page crop-box: {}", crop);

                    LOG.trace("Page to be rotated this amount: {}\u00B0", page.getRotation());

                    final PDImageXObject img = (PDImageXObject) obj;
                    img.getImage().
                    LOG.trace("Image dimensions: [{},{}]", img.getWidth(), img.getHeight());
                    return rotateImage(img.getImage(), page.getRotation()/90);
                }
            }
        }
        LOG.warn("Could not find image on page {}.", iPage);
        throw new IOException();
    }

    private static void verifyPageInRange(final int iPage, final int cPage) {
        if (!(0 <= iPage && iPage < cPage)) {
            LOG.error("Page number {} out of range 1 to {} (inclusive).", iPage + 1, cPage);
            throw new IndexOutOfBoundsException();
        }
    }

    private static BufferedImage rotateImage(final BufferedImage img, final int rot) {
        if (rot == 0) {
            return img;
        }

        Dimension dim = new Dimension(img.getWidth(), img.getHeight());
        if (rot == 1 || rot == 3) {
            dim = swapDim(dim);
        }
        final BufferedImage destImage = new BufferedImage(dim.width, dim.height, BufferedImage.TYPE_INT_ARGB);
        rotateImageInto(img, rot, destImage);
        return destImage;
    }

    private static void rotateImageInto(final BufferedImage img, final int rot, final BufferedImage destImage) {
        final Graphics2D graphics = destImage.createGraphics();
        final AffineTransform transform = new AffineTransform();
        LOG.debug("Rotation quadrant (1-3 = 90,180,270): {}", rot);
        if (rot == 1) {
            transform.translate(img.getHeight(), 0);
        } else if (rot == 2) {
            transform.translate(img.getWidth(), img.getHeight());
        } else if (rot == 3) {
            transform.translate(0, img.getWidth());
        }
        transform.quadrantRotate(rot);
        graphics.drawRenderedImage(img, transform);
        graphics.dispose();
    }

    private static Dimension swapDim(final Dimension dim) {
        return new Dimension(dim.height, dim.width);
    }
}

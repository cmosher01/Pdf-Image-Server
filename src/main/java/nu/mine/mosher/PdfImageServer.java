package nu.mine.mosher;

import fi.iki.elonen.NanoHTTPD;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
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
            return Paths
                .get(System.getProperty("user.dir", "./"))
                .toAbsolutePath()
                .normalize()
                .toRealPath();
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
                    return newChunkedResponse(Response.Status.OK, "image/png", getImageFromPage(path, iPage));
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
        final List<String> ps = session
            .getParameters()
            .getOrDefault("page", Collections.singletonList("1"));
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
        final String uri = removeLeadingSlash(session.getUri());
        LOG.trace("Received request for URI: {}", uri);
        final Path path = CWD
            .resolve(Paths.get(uri))
            .toAbsolutePath()
            .normalize();
        LOG.trace("Resolved: {} to: {}", uri, path.toString());
        final Path pathReal = path.toRealPath();
        LOG.trace("File exists: {}", pathReal.toString());
        verifyNoDirectoryTraversal(pathReal);
        verifyIsFile(pathReal);
        return pathReal;
    }

    private static void verifyIsFile(final Path path) throws IOException {
        if (!(
            path
                .toFile()
                .isFile() && Files.isReadable(path))) {
            throw new IOException("File is not a file (or is not readable.");
        }
    }

    private static void verifyNoDirectoryTraversal(final Path path) throws IOException {
        if (!(path.startsWith(CWD))) {
            throw new IOException("Detected directory-traversal attempt.");
        }
    }

    private static String removeLeadingSlash(final String uri) {
        return uri.startsWith("/") ? uri.substring(1) : uri;
    }

    private static InputStream getImageFromPage(final Path pathPdf, final int iPage) throws IOException {
        final PipedInputStream in = new PipedInputStream();
        final PipedOutputStream out = new PipedOutputStream(in);
        new Thread(() -> writeImage(pathPdf, iPage, out)).start();
        return in;
    }

    private static void writeImage(final Path pathPdf, final int iPage, final PipedOutputStream to) {
        try (final PDDocument doc = PDDocument.load(pathPdf.toFile())) {
            final int cPage = doc.getNumberOfPages();
            LOG.trace("Document has {} pages", cPage);
            if (0 <= iPage && iPage < cPage) {
                final PDPage page = doc.getPage(iPage);
                final PDResources rsrs = page.getResources();
                for (final COSName name : rsrs.getXObjectNames()) {
                    final PDXObject obj = rsrs.getXObject(name);
                    if (obj instanceof PDImageXObject) {
                        final BufferedImage img = ((PDImageXObject) obj).getImage();
                        final BufferedImage rot = page.getRotation()==0 ? img : rotateImage(img, page.getRotation());
                        ImageIO.write(rot, "png", to);
                    }
                }
            } else {
                LOG.error("Page number {} out of range 0 to {} (inclusive).", iPage, cPage - 1);
                throw new IOException("Page number out of range.");
            }
        } catch (final Throwable e) {
            LOG.error("Error extracting image", e);
        }
        ImageWriterSpi
    }

    private static BufferedImage rotateImage(BufferedImage sourceImage, double angle) {
        // TODO fix rotation (need to swap width/height for rotation of 90 or 270)
        // and simplify because we need to handle only 0, 90, 180, 270
        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();
        BufferedImage destImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = destImage.createGraphics();

        AffineTransform transform = new AffineTransform();
        transform.rotate(angle / 180 * Math.PI, width / 2 , height / 2);
        g2d.drawRenderedImage(sourceImage, transform);

        g2d.dispose();
        return destImage;
    }
}

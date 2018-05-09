# Pdf-Image-Server
Serves images from pages of a PDF file.

This is an HTTP server that only reads PDF files, and serves images from individual pages.

This application is useful if you have a PDF file that can be treated as merely a container for images.
The most useful format of the PDF file will be where each page simply has an image object as its contents.
Then, given a URL with `page` query parameter as a page number, this application will serve the (first)
image object from page number `page` as a `PNG`.

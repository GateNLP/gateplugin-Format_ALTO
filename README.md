# GATE Support for ALTO XML documents

This plugin provides support for reading documents stored as [ALTO XML](http://loc.gov/standards/alto). The format is usually used to store OCR based transcriptions of documents and hence contains information on the position within the page of the text as well as the text itself. It's popular among libraries and museums as a way of providing digital copies of scanned document and manuscripts. For example, the [British Libray](https://www.bl.uk/) offers a number of [collections of digitised books](https://data.bl.uk/digbks/) in this format.

The code provided by this plugin focuses purely on the text content of ALTO XML files and completely ignores the positional information. Specifically it reads the `String` elements that appear within `TextBlock` elements that are within the `PrintSpace` of each page. This means that text in the header, footer, and margins are ignored. This is based on previous experiance with processing multi-page formats (such as PDFs) where the header and footer make the processing of text which flows across pages exceptionally problematic. This may change in future versions.

To activate the plugin (once loaded) set the mime type to `application/xml+alto` when loading documents.

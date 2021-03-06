/*
 * Copyright (c) 2019, The University of Sheffield. See the file COPYRIGHT.txt
 * in the software or at http://gate.ac.uk/gate/COPYRIGHT.txt
 *
 * This file is part of GATE (see http://gate.ac.uk/), and is free software,
 * licenced under the GNU Library General Public License, Version 3, June 2007
 * (in the distribution as file licence.html, and also available at
 * http://gate.ac.uk/gate/licence.html).
 */
package gate.corpora;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import gate.AnnotationSet;
import gate.Document;
import gate.Factory;
import gate.FeatureMap;
import gate.GateConstants;
import gate.Resource;
import gate.TextualDocument;
import gate.creole.ResourceInstantiationException;
import gate.creole.metadata.AutoInstance;
import gate.creole.metadata.CreoleResource;
import gate.util.BomStrippingInputStreamReader;
import gate.util.DocumentFormatException;
import gate.util.InvalidOffsetException;
import gate.util.Out;

/**
 * A GATE document format that can handle ALTO XML files. These usually hold
 * information from an OCR process and are popular in histroical archiving
 * projects such as those from the British Library.
 * 
 * @author Mark A. Greenwood
 */
@CreoleResource(name = "ALTO XML Document Format", isPrivate = true, autoinstances = {
    @AutoInstance(hidden = true)}, comment = "Format parser for ALTO XML files")
public class ALTOXMLDocumentFormat extends TextualDocumentFormat {

  private static final long serialVersionUID = -5296246827201634489L;

  @Override
  public Resource init() throws ResourceInstantiationException {

    MimeType mime = new MimeType("application", "xml+alto");
    // Register the class handler for this MIME-type
    mimeString2ClassHandlerMap.put(mime.getType() + "/" + mime.getSubtype(),
        this);
    // Register the mime type with string
    mimeString2mimeTypeMap.put(mime.getType() + "/" + mime.getSubtype(), mime);

    magic2mimeTypeMap.put("<alto", mime);

    setMimeType(mime);

    return this;
  }

  @Override
  public void unpackMarkup(Document doc) throws DocumentFormatException {
    unpackMarkup(doc, null, null);
  }

  @Override
  public void unpackMarkup(Document doc, RepositioningInfo repInfo,
      RepositioningInfo ampCodingInfo) throws DocumentFormatException {
    if((doc == null)
        || (doc.getSourceUrl() == null && doc.getContent() == null))
      throw new DocumentFormatException(
          "ALTO v2 XML document is null or no content found. Nothing to parse!");

    try {
      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();

      org.w3c.dom.Document altoDocument = null;

      if(hasContentButNoValidUrl(doc)) {
        // the doc must have been created from a String
        try (StringReader inputReader =
            new StringReader(doc.getContent().toString())) {
          altoDocument = dBuilder.parse(new InputSource(inputReader));
        }
      } else if(doc instanceof TextualDocument) {
        String encoding = ((TextualDocument)doc).getEncoding();
        try (BufferedReader inputReader =
            new BomStrippingInputStreamReader(doc.getSourceUrl().openStream(), encoding)) {
          altoDocument = dBuilder.parse(new InputSource(inputReader));
        }

      } else {
        // not a TextualDocument, so let parser determine encoding
        try (InputStream inputStream = doc.getSourceUrl().openStream()) {
          altoDocument = dBuilder.parse(inputStream);
        }
      }

      StringBuilder content = new StringBuilder();
      List<TempAnnotation> annotations = new ArrayList<TempAnnotation>();

      long pageStart = 0;
      long blockStart = 0;

      // currently we ignore things that aren't the main content, so instead of
      // accessing the Page element (and getting the margins) we access the
      // PrintSpace element and process each in turn
      NodeList pages = altoDocument.getElementsByTagName("PrintSpace");

      for(int p = 0; p < pages.getLength(); ++p) {
        Element page = (Element)pages.item(p);

        // if this isn't the first page add some new lines
        if(p > 0) content.append("\n\n");

        // record the offset of the start of the text content of the page
        pageStart = content.length();

        // within each page we select each TextBlock, which we assume is like a
        // paragraph and will need new lines after each one
        NodeList textBlocks = page.getElementsByTagName("TextBlock");

        for(int b = 0; b < textBlocks.getLength(); ++b) {
          Element textBlock = (Element)textBlocks.item(b);

          // if this isn't the first block then add some new lines
          if(b > 0) content.append("\n\n");

          // record the start offset of the text in this block
          blockStart = content.length();

          // within each block we pull out all the String elements skipping
          // spaces and hyphens etc. which we handle separately
          NodeList tokens = textBlock.getElementsByTagName("String");

          long lineStart = content.length();

          for(int t = 0; t < tokens.getLength(); ++t) {
            Element token = (Element)tokens.item(t);

            // get the normal content of the token
            String tokenString = token.getAttribute("CONTENT");

            if(token.hasAttribute("SUBS_CONTENT")) {
              // if there is substitution content...

              // get the type of the substitution
              String type = token.getAttribute("SUBS_TYPE");

              // currently we only handle abbreviations, and there we only need
              // the first part so for anything else skip this token entirely
              if(!type.equalsIgnoreCase("HypPart1")) continue;

              // we are on the first part of a hyphenation so use the
              // substitution content instead of the original
              tokenString = token.getAttribute("SUBS_CONTENT");

            }

            if(t > 0) content.append(" ");

            // lets peak and see if there is another token after this one within
            // the same TextBlock
            Element nextToken = (Element)tokens.item(t + 1);

            if(nextToken == null
                || !token.getParentNode().equals(nextToken.getParentNode())) {
              // a TextLine ends if we've either reached the end of the
              // TextBlock (i.e. no more tokens) or if the parent of this and
              // the next token are different. In either case...

              // we store the info for the TextLine using the length of the
              // original content not any substitution
              annotations.add(new TempAnnotation("TextLine", lineStart,
                  content.length() + token.getAttribute("CONTENT").length(),
                  (Element)token.getParentNode(), "ID"));

              // the next TextLine starts either where we've just finished (i.e.
              // we were in the middle of a hyphenated line ending) or after
              // another space which will account for at this point even though
              // we've not added it to the content yet
              lineStart =
                  content.length() + token.getAttribute("CONTENT").length()
                      + (!tokenString.equals(token.getAttribute("CONTENT"))
                          ? 0
                          : 1);
            }

            // store the info we will need to annotate the token
            annotations.add(new TempAnnotation("String", content.length(),
                content.length() + tokenString.length(), token, "ID", "WC",
                "CC"));

            // append the string to the content
            content.append(tokenString);
          }

          // store the info needed to create the annotation for the block
          annotations.add(new TempAnnotation("TextBlock", blockStart,
              content.length(), textBlock, "ID"));
        }

        // store the info needed to create the annotation for the page
        annotations.add(new TempAnnotation("Page", pageStart, content.length(),
            page, "ID", "ACCURACY"));
      }

      // set the document content to the extracted text
      doc.setContent(new DocumentContentImpl(content.toString()));

      // create the annotations in "Original markups"
      AnnotationSet originalMarkups = doc.getAnnotations("Original markups");
      for(TempAnnotation annotation : annotations) {
        originalMarkups.add(annotation.start, annotation.end, annotation.type,
            annotation.features);
      }

    } catch(IOException e) {
      throw new DocumentFormatException(e);
    } catch(ParserConfigurationException | SAXException e) {
      doc.getFeatures().put("parsingError", Boolean.TRUE);

      Boolean bThrow = (Boolean)doc.getFeatures()
          .get(GateConstants.THROWEX_FORMAT_PROPERTY_NAME);

      if(bThrow != null && bThrow.booleanValue()) {
        throw new DocumentFormatException(e);
      } else {
        Out.println(
            "Warning: Document remains unparsed. \n" + "\n  Stack Dump: ");
        e.printStackTrace(Out.getPrintWriter());
      }
    } catch(InvalidOffsetException e) {
      // this should be impossible!
      e.printStackTrace();
    }

  }

  @Override
  public void cleanup() {
    super.cleanup();

    MimeType mime = getMimeType();

    // remove the registration but only if it's still regsitered to us
    mimeString2ClassHandlerMap.remove(mime.getType() + "/" + mime.getSubtype());
    mimeString2mimeTypeMap.remove(mime.getType() + "/" + mime.getSubtype());
    magic2mimeTypeMap.remove("<alto", mime);
  }

  /**
   * A simple class for storing info needed to create an annotation
   */
  private static class TempAnnotation {
    long start, end;

    String type;

    FeatureMap features;

    public TempAnnotation(String type, long start, long end, Element element,
        String... attributes) {
      this.type = type;
      this.start = start;
      this.end = end;

      features = Factory.newFeatureMap();

      for(int i = 0; i < attributes.length; ++i) {
        if(element.hasAttribute(attributes[i])) {
          features.put(attributes[i], element.getAttribute(attributes[i]));
        }
      }
    }
  }
}

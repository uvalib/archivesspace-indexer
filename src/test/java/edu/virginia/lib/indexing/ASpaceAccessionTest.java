package edu.virginia.lib.indexing;

import edu.virginia.lib.indexing.helpers.SolrHelper;
import edu.virginia.lib.indexing.helpers.XmlHelper;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.stream.XMLStreamException;
import javax.xml.xpath.XPathExpressionException;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import static edu.virginia.lib.indexing.helpers.JsonHelper.parseJsonObject;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * Tests for the parsing and production of Solr index documents
 * for Archvies Space accession records.
 */
public class ASpaceAccessionTest {

    private static final String REF = "/repositories/0/accessions/0";
    private static final String REPOSITORY_REF = "/repositories/0";

    private static final File OUTPUT_DIR = new File("target/test-output/");

    @Mock ArchivesSpaceClient client;

    @Before
    public void init() throws IOException {
        initMocks(this);

        when(client.resolveReference(REF)).thenReturn(
                parseJsonObject(getClass().getClassLoader().getResourceAsStream("accession0.json")));
        when(client.resolveReference(REPOSITORY_REF)).thenReturn(
                parseJsonObject(getClass().getClassLoader().getResourceAsStream("repository0.json")));
    }

    @Test
    public void testRequiredFields() throws IOException, XMLStreamException, SQLException, XPathExpressionException, SAXException {
        ASpaceAccession a = new ASpaceAccession(client, REF);

        Document xmlDoc = XmlHelper.parseXmlFile(a.generateV4SolrAddDoc(OUTPUT_DIR, null, null, null));

        List<String> ids = SolrHelper.getSolrXmlFieldValues("id", xmlDoc);
        assertEquals(1, ids.size());
        assertEquals("as:0a0", ids.get(0));

    }

}

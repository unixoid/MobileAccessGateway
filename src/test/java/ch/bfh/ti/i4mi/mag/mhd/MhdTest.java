package ch.bfh.ti.i4mi.mag.mhd;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ICriterion;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ch.bfh.ti.i4mi.mag.MobileAccessGateway;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openehealth.ipf.commons.ihe.xacml20.model.PpqConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Dmytro Rud
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(MobileAccessGateway.class)
@ActiveProfiles("test")
@Slf4j
public class MhdTest {

    @Autowired
    protected CamelContext camelContext;

    @Autowired
    protected ProducerTemplate producerTemplate;

    @Value("${server.port}")
    protected Integer serverPort;

    @BeforeAll
    public static void beforeAll() {
        Locale.setDefault(Locale.ENGLISH);

//        System.setProperty("javax.net.ssl.keyStore", "src/main/resources/example-server-certificate.p12");
//        System.setProperty("javax.net.ssl.keyStorePassword", "a1b2c3");
//        System.setProperty("javax.net.ssl.trustStore", "src/main/resources/example-server-certificate.p12");
//        System.setProperty("javax.net.ssl.trustStorePassword", "a1b2c3");
    }

    @Test
    public void testMetadataUpdate() {
        DocumentReference documentReference = (DocumentReference) FhirContext.forR4().newJsonParser().parseResource(MhdTest.class.getClassLoader().getResourceAsStream("update-request-1.json"));
        IGenericClient client = FhirContext.forR4().newRestfulGenericClient("http://localhost:" + serverPort + "/fhir");
        MethodOutcome methodOutcome;

        // test success case
        documentReference.getContent().get(0).getAttachment().setSize(100);
        methodOutcome = client.update()
                .resource(documentReference)
                .execute();
        assertEquals(200, methodOutcome.getResponseStatusCode());

        // test failure case
        boolean errorCatched = false;
        try {
            documentReference.getContent().get(0).getAttachment().setSize(101);
            methodOutcome = client.update()
                    .resource(documentReference)
                    .execute();
        } catch (BaseServerResponseException e) {
            assertEquals(400, e.getStatusCode());
            errorCatched = true;
        }
        assertTrue(errorCatched);
    }

    @Test
    public void testDocumentDeletion() throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            ClassicHttpRequest httpRequest = ClassicRequestBuilder.delete("http://localhost:" + serverPort + "/fhir/DocumentReference/metadata-update-test-1")
                    .setCharset(StandardCharsets.UTF_8)
                    .build();
            String response = httpClient.execute(httpRequest, httpResponse -> {
                assertEquals(200, httpResponse.getCode());
                return httpResponse.toString();
            });
            log.info(response);
        }
    }

    @Test
    public void testDeletionFlagFitering() throws Exception {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getMessage().setBody(new ICriterion[]{
                DocumentReference.PATIENT.hasId("Patient/urn:oid:" + PpqConstants.CodingSystemIds.SWISS_PATIENT_ID + "-deletion-flag-filtering-test-1"),
                DocumentReference.STATUS.exactly().identifier("current"),
        });
        exchange = producerTemplate.send("mhd-iti67://localhost:" + serverPort + "/fhir", exchange);
        if (exchange.getException() != null) {
            throw exchange.getException();
        }
        Bundle bundle = exchange.getMessage().getMandatoryBody(Bundle.class);

        int[] expectedIds = new int[]{0, 1, 3};
        assertEquals(expectedIds.length, bundle.getEntry().size());
        for (int i = 0; i < expectedIds.length; ++i) {
            DocumentReference documentReference = (DocumentReference) bundle.getEntry().get(i).getResource();
            assertEquals("urn:oid:uniqueId" + expectedIds[i], documentReference.getMasterIdentifier().getValue());
        }
    }

}

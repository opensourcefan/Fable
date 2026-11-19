package org.fable.service.metadata.parser;


import org.fable.model.dto.Book;
import org.fable.model.dto.BookMetadata;
import org.fable.model.dto.request.FetchMetadataRequest;
import org.fable.model.dto.settings.AppSettings;
import org.fable.model.dto.settings.MetadataProviderSettings;
import org.fable.model.dto.settings.MetadataPublicReviewsSettings;
import org.fable.service.appsettings.AppSettingService;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AmazonBookParserTest {
    @Mock private AppSettingService mockAppSettingService;

    @InjectMocks
    private AmazonBookParser amazonBookParser;

    private MockedStatic<Jsoup> mockJsoup;

    private AppSettings getAppSettings(String domain) {
        MetadataProviderSettings.Amazon amazonSettings = new MetadataProviderSettings.Amazon();
        amazonSettings.setEnabled(true);
        amazonSettings.setDomain(domain);

        MetadataProviderSettings metadataProviderSettings = new MetadataProviderSettings();
        metadataProviderSettings.setAmazon(amazonSettings);

        MetadataPublicReviewsSettings publicReviewsSettings = MetadataPublicReviewsSettings.builder()
                .providers(Collections.emptySet())
                .build();

        return AppSettings
                .builder()
                .metadataPublicReviewsSettings(publicReviewsSettings)
                .metadataProviderSettings(metadataProviderSettings)
                .build();
    }

    private Book getBook(String asin) {
        BookMetadata bookMetadata = BookMetadata.builder()
                .asin(asin)
                .build();

        return Book.builder()
                .title("Example")
                .metadata(bookMetadata)
                .build();
    }

    private Connection getConnection(Document document) throws IOException {
        Connection mockConnection = mock(Connection.class);

        Connection.Response mockResponse = mock(Connection.Response.class);

        when(mockConnection.timeout(anyInt())).thenReturn(mockConnection);
        when(mockConnection.header(any(String.class), any(String.class))).thenReturn(mockConnection);
        when(mockConnection.method(any(Connection.Method.class))).thenReturn(mockConnection);
        when(mockConnection.execute()).thenReturn(mockResponse);

        when(mockResponse.parse()).thenReturn(document);

        return mockConnection;
    }

    private void mockJsoupConnect(String url, String html) throws Exception {
        Document document = Parser.parse(html, "");
        Connection connection = getConnection(document);

        mockJsoup.when(() -> Jsoup.connect(url))
                .thenReturn(connection);
    }

    private void mockAmazonIDSearch(String keyword) throws Exception {
        mockJsoupConnect(
                "https://www.amazon.com/s?k=" + keyword,
                """
                <html><body>
                <span data-component-type="s-search-results">
                <div div role="listitem" data-index>
                    <div data-cy="title-recipe">Example</div>
                    <a href="https://www.amazon.com/dp/SEARCHASIN">Paperback</a>
                </div>
                </span>
                </body></html>
                """
        );

    }

    @BeforeEach
    public void setup() throws Exception {
        when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettings( "com"));

        mockJsoup = mockStatic(Jsoup.class);
    }

    @AfterEach
    void tearDown() {
        mockJsoup.close();
    }

    @Test
    public void fetchTopMetadata_usesAsinFromBookWhenAvailable() throws Exception {
        mockJsoupConnect("https://www.amazon.com/dp/EXAMPLESKU", "<html />");

        Book book = getBook("EXAMPLESKU");
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().build();

        amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/dp/EXAMPLESKU"));
    }

    @Test
    public void fetchTopMetadata_useDomain() throws Exception {
        mockJsoupConnect("https://www.amazon.co.jp/dp/EXAMPLESKU", "<html />");
        when(mockAppSettingService.getAppSettings()).thenReturn(getAppSettings( "co.jp"));

        Book book = getBook("EXAMPLESKU");
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().build();

        amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.co.jp/dp/EXAMPLESKU"));
    }

    @Test
    public void fetchTopMetadata_removesExtraWhitespace() throws Exception {
        mockJsoupConnect("https://www.amazon.com/dp/EXAMPLESKU", "<html />");

        Book book = getBook("  EXAMPLESKU  ");
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().build();

        amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/dp/EXAMPLESKU"));
    }

    @Test
    public void fetchTopMetadata_removesExtraCharacters() throws Exception {
        mockJsoupConnect("https://www.amazon.com/dp/EXAMPLESKU", "<html />");

        Book book = getBook("@EXAMPLESKU!!");
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().build();

        amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/dp/EXAMPLESKU"));
    }

    @Test
    public void fetchTopMetadata_ignoresInvalidBookASINUsesLink() throws Exception {
        mockAmazonIDSearch("Example");
        mockJsoupConnect("https://www.amazon.com/dp/SEARCHASIN", "<html />");

        // Not 10 characters, alpha-numeric.
        Book book = getBook("bad asin");
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().title("Example").build();

        amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/dp/SEARCHASIN"));
    }

    @Test
    public void fetchTopMetadata_ignoresMissingBookASINUsesLink() throws Exception {
        mockAmazonIDSearch("Example");
        mockJsoupConnect("https://www.amazon.com/dp/SEARCHASIN", "<html />");

        Book book = getBook(null);
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().title("Example").build();

        amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);

        mockJsoup.verify(() -> Jsoup.connect("https://www.amazon.com/dp/SEARCHASIN"));
    }

    @Test
    public void fetchTopMetadata_handlesNullDescriptionAndParsesDates() throws Exception {
        String html = """
                <html>
                <body>
                    <div id="detailBullets_feature_div">
                        <ul>
                            <li>
                                <span class="a-text-bold">Publisher ‏ : ‎</span>
                                <span>IDW Publishing (May 3 2011)</span>
                            </li>
                            <li>
                                <span class="a-text-bold">Dimensions ‏ : ‎</span>
                                <span>16.76 x 1.78 x 25.65 cm</span>
                            </li>
                            <li>
                                <span class="a-text-bold">Language ‏ : ‎</span>
                                <span>English</span>
                            </li>
                        </ul>
                    </div>
                </body>
                </html>
                """;
        mockJsoupConnect("https://www.amazon.com/dp/EXAMPLESKU", html);

        Book book = getBook("EXAMPLESKU");
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().build();

        BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);
        
        org.junit.jupiter.api.Assertions.assertNotNull(metadata);
        org.junit.jupiter.api.Assertions.assertNull(metadata.getDescription());
        org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalDate.of(2011, 5, 3), metadata.getPublishedDate());
    }

    @Test
    public void fetchTopMetadata_parsesDateFormatsWithoutCommas() throws Exception {
        String html = """
                <html>
                <body>
                    <div id="detailBullets_feature_div">
                        <ul>
                            <li>
                                <span class="a-text-bold">Publication Date ‏ : ‎</span>
                                <span>Feb. 1 2023</span>
                            </li>
                        </ul>
                    </div>
                </body>
                </html>
                """;
        mockJsoupConnect("https://www.amazon.com/dp/EXAMPLESKU", html);

        Book book = getBook("EXAMPLESKU");
        FetchMetadataRequest fetchMetadataRequest = FetchMetadataRequest.builder().build();

        BookMetadata metadata = amazonBookParser.fetchTopMetadata(book, fetchMetadataRequest);
        
        org.junit.jupiter.api.Assertions.assertNotNull(metadata);
        org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalDate.of(2023, 2, 1), metadata.getPublishedDate());
    }
}


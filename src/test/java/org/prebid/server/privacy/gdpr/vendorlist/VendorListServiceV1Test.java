package org.prebid.server.privacy.gdpr.vendorlist;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;
import org.prebid.server.VertxTest;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.privacy.gdpr.vendorlist.proto.VendorListV1;
import org.prebid.server.privacy.gdpr.vendorlist.proto.VendorV1;
import org.prebid.server.vertx.http.HttpClient;
import org.prebid.server.vertx.http.model.HttpClientResponse;

import java.io.File;
import java.util.Date;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.prebid.server.assertion.FutureAssertion.assertThat;

public class VendorListServiceV1Test extends VertxTest {

    private static final String CACHE_DIR = "/cache/dir";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private FileSystem fileSystem;
    @Mock
    private HttpClient httpClient;
    @Mock
    private BidderCatalog bidderCatalog;

    private VendorListService<VendorListV1, VendorV1> vendorListService;

    @Before
    public void setUp() {
        given(fileSystem.existsBlocking(anyString())).willReturn(false); // always create cache dir

        given(bidderCatalog.knownVendorIds()).willReturn(singleton(52));

        vendorListService = new VendorListServiceV1(CACHE_DIR, "http://vendorlist/{VERSION}", 0, null, bidderCatalog,
                fileSystem, httpClient, jacksonMapper);
    }

    // Creation related tests

    @Test
    public void creationShouldFailsIfCannotCreateCacheDir() {
        // given
        given(fileSystem.mkdirsBlocking(anyString())).willThrow(new RuntimeException("dir creation error"));

        // then
        assertThatThrownBy(
                () -> new VendorListServiceV1(CACHE_DIR, "http://vendorlist/%s", 0, null, bidderCatalog, fileSystem,
                        httpClient, jacksonMapper))
                .hasMessage("dir creation error");
    }

    @Test
    public void creationShouldFailsIfCannotReadFiles() {
        // given
        given(fileSystem.readDirBlocking(anyString())).willThrow(new RuntimeException("read error"));

        // then
        assertThatThrownBy(
                () -> new VendorListServiceV1(CACHE_DIR, "http://vendorlist/%s", 0, null, bidderCatalog, fileSystem,
                        httpClient, jacksonMapper))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("read error");
    }

    @Test
    public void creationShouldFailsIfCannotReadAtLeastOneVendorListFile() {
        // given
        given(fileSystem.readDirBlocking(anyString())).willReturn(singletonList("1.json"));
        given(fileSystem.readFileBlocking(anyString())).willThrow(new RuntimeException("read error"));

        // then
        assertThatThrownBy(
                () -> new VendorListServiceV1(CACHE_DIR, "http://vendorlist/%s", 0, null, bidderCatalog, fileSystem,
                        httpClient, jacksonMapper))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("read error");
    }

    @Test
    public void creationShouldFailsIfAtLeastOneVendorListFileCannotBeParsed() {
        // given
        given(fileSystem.readDirBlocking(anyString())).willReturn(singletonList("1.json"));
        given(fileSystem.readFileBlocking(anyString())).willReturn(Buffer.buffer("invalid"));

        // then
        assertThatThrownBy(
                () -> new VendorListServiceV1(CACHE_DIR, "http://vendorlist/%s", 0, null, bidderCatalog, fileSystem,
                        httpClient, jacksonMapper))
                .isInstanceOf(PreBidException.class)
                .hasMessage("Cannot parse vendor list from: invalid");
    }

    // Http related tests

    @Test
    public void shouldPerformHttpRequestWithExpectedQueryIfVendorListNotFound() {
        // given
        givenHttpClientReturnsResponse(200, null);

        // when
        vendorListService.forVersion(1);

        // then
        verify(httpClient).get(eq("http://vendorlist/1"), anyLong());
    }

    @Test
    public void shouldNotAskToSaveFileIfReadingHttpResponseFails() {
        // given
        givenHttpClientProducesException(new RuntimeException("Response exception"));

        // when
        vendorListService.forVersion(1);

        // then
        verify(httpClient).get(anyString(), anyLong());
        verify(fileSystem, never()).writeFile(any(), any(), any());
    }

    @Test
    public void shouldNotAskToSaveFileIfResponseCodeIsNot200() {
        // given
        givenHttpClientReturnsResponse(503, "response");

        // when
        vendorListService.forVersion(1);

        // then
        verify(httpClient).get(anyString(), anyLong());
        verify(fileSystem, never()).writeFile(any(), any(), any());
    }

    @Test
    public void shouldNotAskToSaveFileIfResponseBodyCouldNotBeParsed() {
        // given
        givenHttpClientReturnsResponse(200, "response");

        // when
        vendorListService.forVersion(1);

        // then
        verify(httpClient).get(anyString(), anyLong());
        verify(fileSystem, never()).writeFile(any(), any(), any());
    }

    @Test
    public void shouldNotAskToSaveFileIfFetchedVendorListHasInvalidVendorListVersion() throws JsonProcessingException {
        // given
        final VendorListV1 vendorList = VendorListV1.of(null, null, null);
        givenHttpClientReturnsResponse(200, mapper.writeValueAsString(vendorList));

        // when
        vendorListService.forVersion(1);

        // then
        verify(httpClient).get(anyString(), anyLong());
        verify(fileSystem, never()).writeFile(any(), any(), any());
    }

    @Test
    public void shouldNotAskToSaveFileIfFetchedVendorListHasInvalidLastUpdated() throws JsonProcessingException {
        // given
        final VendorListV1 vendorList = VendorListV1.of(1, null, null);
        givenHttpClientReturnsResponse(200, mapper.writeValueAsString(vendorList));

        // when
        vendorListService.forVersion(1);

        // then
        verify(httpClient).get(anyString(), anyLong());
        verify(fileSystem, never()).writeFile(any(), any(), any());
    }

    @Test
    public void shouldNotAskToSaveFileIfFetchedVendorListHasNoVendors() throws JsonProcessingException {
        // given
        final VendorListV1 vendorList = VendorListV1.of(1, new Date(), null);
        givenHttpClientReturnsResponse(200, mapper.writeValueAsString(vendorList));

        // when
        vendorListService.forVersion(1);

        // then
        verify(httpClient).get(anyString(), anyLong());
        verify(fileSystem, never()).writeFile(any(), any(), any());
    }

    @Test
    public void shouldNotAskToSaveFileIfFetchedVendorListHasEmptyVendors() throws JsonProcessingException {
        // given
        final VendorListV1 vendorList = VendorListV1.of(1, new Date(), emptyList());
        givenHttpClientReturnsResponse(200, mapper.writeValueAsString(vendorList));

        // when
        vendorListService.forVersion(1);

        // then
        verify(httpClient).get(anyString(), anyLong());
        verify(fileSystem, never()).writeFile(any(), any(), any());
    }

    @Test
    public void shouldNotAskToSaveFileIfFetchedVendorListHasAtLeastOneInvalidVendor() throws JsonProcessingException {
        // given
        final VendorListV1 vendorList = VendorListV1.of(1, new Date(), singletonList(VendorV1.of(null, null, null)));
        givenHttpClientReturnsResponse(200, mapper.writeValueAsString(vendorList));

        // when
        vendorListService.forVersion(1);

        // then
        verify(httpClient).get(anyString(), anyLong());
        verify(fileSystem, never()).writeFile(any(), any(), any());
    }

    // File system related tests

    @Test
    public void shouldSaveFileWithExpectedPathAndContentIfVendorListNotFound() throws JsonProcessingException {
        // given
        final String vendorListAsString = mapper.writeValueAsString(givenVendorList());
        givenHttpClientReturnsResponse(200, vendorListAsString);
        // generate file path to avoid conflicts with path separators in different OS
        final String filePath = new File("/cache/dir/1.json").getPath();

        // when
        vendorListService.forVersion(1);

        // then
        verify(fileSystem).writeFile(eq(filePath), eq(Buffer.buffer(vendorListAsString)), any());
    }

    // In-memory cache related tests

    @Test
    public void shouldFailIfVendorListIsBelowOrZero() {
        // given
        givenHttpClientProducesException(new RuntimeException());

        // when
        final Future<?> result1 = vendorListService.forVersion(0);
        final Future<?> result2 = vendorListService.forVersion(-2);

        // then
        assertThat(result1).isFailed().hasMessage("Vendor list for version 0 not valid.");
        assertThat(result2).isFailed().hasMessage("Vendor list for version -2 not valid.");
    }

    @Test
    public void shouldFailIfVendorListNotFound() {
        // given
        givenHttpClientProducesException(new RuntimeException());

        // when
        final Future<Map<Integer, VendorV1>> future = vendorListService.forVersion(1);

        // then
        assertThat(future).isFailed().hasMessage("Vendor list for version 1 not fetched yet, try again later.");
    }

    @Test
    public void shouldReturnVendorListFromCache() throws JsonProcessingException {
        // given
        givenHttpClientReturnsResponse(200, mapper.writeValueAsString(givenVendorList()));

        given(fileSystem.writeFile(anyString(), any(), any()))
                .willAnswer(withSelfAndPassObjectToHandler(Future.succeededFuture()));

        // when
        vendorListService.forVersion(1); // populate cache
        final Future<Map<Integer, VendorV1>> result = vendorListService.forVersion(1);

        // then
        assertThat(result).succeededWith(singletonMap(52, VendorV1.of(52, singleton(1), singleton(2))));
    }

    @Test
    public void shouldKeepPurposesOnlyForKnownVendors() throws JsonProcessingException {
        // given
        final VendorListV1 vendorList = VendorListV1.of(1, new Date(),
                asList(VendorV1.of(52, singleton(1), singleton(2)), VendorV1.of(42, singleton(1), singleton(2))));
        givenHttpClientReturnsResponse(200, mapper.writeValueAsString(vendorList));

        given(fileSystem.writeFile(anyString(), any(), any()))
                .willAnswer(withSelfAndPassObjectToHandler(Future.succeededFuture()));

        // when
        vendorListService.forVersion(1); // populate cache
        final Future<Map<Integer, VendorV1>> future = vendorListService.forVersion(1);

        // then
        assertThat(future).succeededWith(singletonMap(52, VendorV1.of(52, singleton(1), singleton(2))));
    }

    private static VendorListV1 givenVendorList() {
        final VendorV1 vendor = VendorV1.of(52, singleton(1), singleton(2));
        return VendorListV1.of(1, new Date(), singletonList(vendor));
    }

    private void givenHttpClientReturnsResponse(int statusCode, String response) {
        given(httpClient.get(anyString(), anyLong()))
                .willReturn(Future.succeededFuture(HttpClientResponse.of(statusCode, null, response)));
    }

    private void givenHttpClientProducesException(Throwable throwable) {
        given(httpClient.get(anyString(), anyLong()))
                .willReturn(Future.failedFuture(throwable));
    }

    @SuppressWarnings("unchecked")
    private static <T> Answer<Object> withSelfAndPassObjectToHandler(T obj) {
        return inv -> {
            // invoking handler right away passing mock to it
            ((Handler<T>) inv.getArgument(2)).handle(obj);
            return inv.getMock();
        };
    }
}

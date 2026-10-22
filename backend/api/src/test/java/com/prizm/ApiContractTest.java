package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import java.io.InputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private CatalogRepository repo;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        repo = mock(CatalogRepository.class);
        when(repo.referenceTime()).thenReturn("2026-10-22T03:00:00Z");
        when(repo.products()).thenReturn(List.of());
        var controller = new ApiController(repo, mapper, "local-test-key", "http://127.0.0.1:8000", mock(AnalysisJobs.class), new ProductCache(), mock(SessionVerifier.class), mock(AnalysisQuota.class));
        mvc = MockMvcBuilders.standaloneSetup(controller, new ApiErrorController())
                .setControllerAdvice(new ApiErrors()).addFilters(new RequestIdFilter()).build();
    }
    @Test void successfulAndFailedResponsesShareHeaderAndBodyRequestIds() throws Exception {
        var success = mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(success.getHeader("X-Request-Id"), mapper.readTree(success.getContentAsString()).at("/meta/requestId").asText());
        var failure = mvc.perform(get("/api/v1/products/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("HTTP_404")).andReturn().getResponse();
        String id = mapper.readTree(failure.getContentAsString()).at("/error/requestId").asText();
        assertDoesNotThrow(() -> UUID.fromString(id)); assertEquals(id, failure.getHeader("X-Request-Id"));
    }
    @Test void malformedQueryAndUnsupportedMethodUseTheSameErrorEnvelope() throws Exception {
        mvc.perform(get("/api/v1/products").param("limit", "not-an-integer"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("HTTP_400"))
                .andExpect(jsonPath("$.error.retryable").value(false));
        mvc.perform(post("/api/v1/products"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.error.code").value("HTTP_405"));
    }
    @Test void unsupportedBodyFormatAndWrongInternalKeyDoNotBecome500() throws Exception {
        mvc.perform(post("/api/v1/internal/fixture-batches").contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error.code").value("HTTP_415"));
        mvc.perform(post("/api/v1/internal/fixture-batches").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("HTTP_403"));
        mvc.perform(post("/api/v1/internal/fixture-batches").header("X-Internal-Key", "local-test-key")
                .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("HTTP_400"));
    }
    @Test void fallbackErrorDispatchAndRetryHeaderFollowTheContract() throws Exception {
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("HTTP_404"));
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 429))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }
    @Test void unexpectedErrorsDoNotExposeExceptionMessages() throws Exception {
        when(repo.products()).thenThrow(new IllegalStateException("private-upstream-key"));
        var response = mvc.perform(get("/api/v1/products")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("HTTP_500")).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains("private-upstream-key"));
    }
    @Test void documentedOperationsMatchControllerRoutesAndReferencesResolve() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/static/openapi.json")) {
            assertNotNull(input); JsonNode spec = mapper.readTree(input);
            Set<String> routes = new HashSet<>();
            for (Class<?> controller : List.of(ApiController.class, MeController.class, AlertController.class, HistoryController.class, AccountDeletionController.class, ChatController.class, DiscoveryController.class, CollectionController.class, SnapshotController.class, CatalogSourceController.class, SnapshotRetentionController.class, OperationsController.class, DanawaController.class, DanawaExplanationController.class, PublicDanawaController.class, DanawaWishlistController.class)) for (var method : controller.getDeclaredMethods()) {
                String prefix = controller.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value()[0].substring("/api/v1".length());
                var get = method.getAnnotation(GetMapping.class);
                var post = method.getAnnotation(PostMapping.class);
                var put = method.getAnnotation(PutMapping.class);
                var delete = method.getAnnotation(DeleteMapping.class);
                var patch = method.getAnnotation(PatchMapping.class);
                if (get != null) routes.add("get " + prefix + (get.value().length == 0 ? "" : get.value()[0]));
                if (post != null) routes.add("post " + prefix + post.value()[0]);
                if (put != null) routes.add("put " + prefix + put.value()[0]);
                if (delete != null) routes.add("delete " + prefix + delete.value()[0]);
                if (patch != null) routes.add("patch " + prefix + patch.value()[0]);
            }
            Set<String> documented = new HashSet<>();
            spec.path("paths").fields().forEachRemaining(path -> path.getValue().fieldNames()
                    .forEachRemaining(verb -> documented.add(verb + " " + path.getKey())));
            assertEquals(routes, documented);
            checkReferences(spec, spec);
        }
    }
    private void checkReferences(JsonNode node, JsonNode root) {
        if (node.isObject() && node.has("$ref")) {
            String reference = node.path("$ref").asText();
            assertTrue(reference.startsWith("#/")); assertFalse(root.at(reference.substring(1)).isMissingNode(), reference);
        }
        if (node.isContainerNode()) node.forEach(child -> checkReferences(child, root));
    }
}







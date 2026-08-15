package org.dar316.spring_ai;

import org.dar316.spring_ai.controller.DocumentationController;
import org.dar316.spring_ai.service.DocumentationIndexer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest(DocumentationController.class)
class DocumentationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ZMIANA 2: Użycie @MockitoBean w miejsce @MockBean
    @MockitoBean
    private DocumentationIndexer documentationIndexer;

    @Test
    void shouldIndexMarkdownFile() throws Exception {
        when(documentationIndexer.index(
                any(MultipartFile.class),
                eq("Redis"),
                eq("7")
        )).thenReturn(3);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "redis.md",
                "text/markdown",
                """
                # Redis

                +++RAG_SECTION+++

                ## SET

                The SET command stores a value.
                """.getBytes()
        );

        mockMvc.perform(
                        multipart("/api/doc/index")
                                .file(file)
                                .param("technology", "Redis")
                                .param("technologyVersion", "7")
                )
                .andExpect(status().isOk())
                .andExpect(
                        content().contentTypeCompatibleWith(
                                MediaType.APPLICATION_JSON
                        )
                )
                .andExpect(jsonPath("$.source").value("redis.md"))
                .andExpect(jsonPath("$.technology").value("Redis"))
                .andExpect(jsonPath("$.technologyVersion").value("7"))
                .andExpect(jsonPath("$.chunks").value(3));

        verify(documentationIndexer).index(
                any(MultipartFile.class),
                eq("Redis"),
                eq("7")
        );
    }

    @Test
    void shouldReturnBadRequestWhenFileIsMissing() throws Exception {
        mockMvc.perform(
                        multipart("/api/doc/index")
                                .param("technology", "Redis")
                                .param("technologyVersion", "7")
                )
                .andExpect(status().isBadRequest());
    }
}

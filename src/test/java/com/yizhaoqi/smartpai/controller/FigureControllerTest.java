package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.parsing.description.FigureImageReader;
import com.yizhaoqi.smartpai.service.FigureAccessService;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FigureControllerTest {
    static final String MD5="f3845f9977bd2f0f31db7ca1100546d2";
    static final String URL="/api/v1/documents/figures/"+MD5+"/1/3/1/image";
    FigureAccessService figures; MockMvc mvc;
    final UsernamePasswordAuthenticationToken user=new UsernamePasswordAuthenticationToken("reader","", List.of());
    @BeforeEach void setup() { figures=mock(FigureAccessService.class); mvc=MockMvcBuilders.standaloneSetup(new FigureController(figures)).build(); }
    @Test void successfulImageUsesAuthenticatedIdentityAndNoStore() throws Exception {
        when(figures.read("reader",MD5,1,3,1)).thenReturn(new FigureImageReader.FigureImage(new byte[]{1,2,3},"image/png"));
        mvc.perform(get(URL).principal(user).param("imagePath","merged/secret"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[]{1,2,3})).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        verify(figures).read("reader",MD5,1,3,1);
    }
    @Test void anonymousIsUnauthorized() throws Exception { mvc.perform(get(URL)).andExpect(status().isUnauthorized()); verifyNoInteractions(figures); }
    @Test void invalidGenerationSyntaxIsBadRequest() throws Exception { mvc.perform(get(URL.replace("/1/3/1/","/abc/3/1/")).principal(user)).andExpect(status().isBadRequest()); }
    @Test void missingFigureAndGenerationAndAccessHaveDistinctStatuses() throws Exception {
        for (var status:List.of(HttpStatus.NOT_FOUND,HttpStatus.CONFLICT,HttpStatus.FORBIDDEN)) {
            doThrow(new ResponseStatusException(status,"safe reason")).when(figures).read("reader",MD5,1,3,1);
            mvc.perform(get(URL).principal(user)).andExpect(status().is(status.value()))
                    .andExpect(jsonPath("$.code").value(status.value())).andExpect(header().string("Cache-Control","no-store"));
        }
    }
}

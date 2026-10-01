package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class UploadInitAuthenticationTest {
    @Test
    void initGetsUserIdFromTokenInsteadOfClientParameter() throws Exception {
        JwtUtils jwt = mock(JwtUtils.class);
        when(jwt.extractUserIdFromToken("token")).thenReturn("1");
        OrgTagAuthorizationFilter filter = new OrgTagAuthorizationFilter();
        ReflectionTestUtils.setField(filter, "jwtUtils", jwt);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/upload/init");
        request.addHeader("Authorization", "Bearer token");
        request.addParameter("userId", "2");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals("1", request.getAttribute("userId"));
    }
}

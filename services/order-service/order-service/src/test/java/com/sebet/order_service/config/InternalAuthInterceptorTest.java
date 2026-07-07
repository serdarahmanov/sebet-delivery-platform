package com.sebet.order_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalAuthInterceptorTest {

    @Test
    void preHandleRejectsMissingInternalKeyHeader() throws Exception {
        InternalAuthInterceptor interceptor = interceptor("secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getErrorMessage()).isEqualTo("Missing X-Internal-Key header");
    }

    @Test
    void preHandleRejectsWrongInternalKeyWhenSecretConfigured() throws Exception {
        InternalAuthInterceptor interceptor = interceptor("secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Key", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).isEqualTo("Invalid X-Internal-Key");
    }

    @Test
    void preHandleAcceptsMatchingInternalKeyWhenSecretConfigured() throws Exception {
        InternalAuthInterceptor interceptor = interceptor("secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Key", "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void preHandleRejectsBlankInternalKeyHeader() throws Exception {
        InternalAuthInterceptor interceptor = interceptor("secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Key", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getErrorMessage()).isEqualTo("Missing X-Internal-Key header");
    }

    @Test
    void validateConfiguredSecretRejectsBlankSecretInEveryProfile() {
        InternalAuthInterceptor interceptor = new InternalAuthInterceptor("");

        assertThatThrownBy(interceptor::validateConfiguredSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("order-service.internal.secret must be configured");
    }

    @Test
    void validateConfiguredSecretRejectsWhitespaceSecretInEveryProfile() {
        InternalAuthInterceptor interceptor = new InternalAuthInterceptor("   ");

        assertThatThrownBy(interceptor::validateConfiguredSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("order-service.internal.secret must be configured");
    }

    @Test
    void validateConfiguredSecretAllowsConfiguredSecretInAnyProfile() {
        InternalAuthInterceptor interceptor = new InternalAuthInterceptor("secret");

        interceptor.validateConfiguredSecret();
    }

    private InternalAuthInterceptor interceptor(String configuredSecret) {
        InternalAuthInterceptor interceptor = new InternalAuthInterceptor(configuredSecret);
        interceptor.validateConfiguredSecret();
        return interceptor;
    }
}

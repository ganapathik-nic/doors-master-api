package org.gepnic.doors.masterapi.security;

import org.gepnic.doors.masterapi.config.*;
import org.gepnic.doors.masterapi.controller.QueryGovernanceController;
import org.gepnic.doors.masterapi.exception.GlobalExceptionHandler;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.*;
import org.gepnic.doors.masterapi.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Actual application SecurityFilterChain with isolated mocked infrastructure; no database/AI startup. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SecurityHttpIntegrationTest.Config.class)
@WebAppConfiguration
class SecurityHttpIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired QueryApprovalService approval;
    @Autowired UserRepository users;
    MockMvc mvc;

    @BeforeEach void setup() {
        reset(approval, users);
        when(users.findByUsername(anyString())).thenAnswer(invocation -> {
            User u = new User(); u.setUsername(invocation.getArgument(0)); u.setRole("Developer");
            u.setIsActive(true); u.setStatus("ACTIVE"); return Optional.of(u);
        });
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void anonymousProposalDenied() throws Exception {
        mvc.perform(post("/api/v1/master/governance/propose").with(csrf())
                .contentType("application/json").content(validBody())).andExpect(status().isUnauthorized());
        verifyNoInteractions(approval);
    }

    @Test void viewerCannotUseDeveloperFunction() throws Exception {
        mvc.perform(post("/api/v1/master/governance/propose").with(csrf())
                .with(user("viewer").authorities(new SimpleGrantedAuthority("DATAVIEWER")))
                .contentType("application/json").content(validBody())).andExpect(status().isForbidden());
        verifyNoInteractions(approval);
    }

    @Test void csrfStillRequiredForPortalMutation() throws Exception {
        mvc.perform(post("/api/v1/master/governance/propose")
                .with(user("dev").authorities(new SimpleGrantedAuthority("DEVELOPER")))
                .contentType("application/json").content(validBody())).andExpect(status().isForbidden());
        verifyNoInteractions(approval);
    }

    @Test void existingIdRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/v1/master/governance/propose").with(csrf())
                .with(user("dev").authorities(new SimpleGrantedAuthority("DEVELOPER")))
                .contentType("application/json").content("""
                        {"queryId":17,"uniqueName":"safe_query","sqlText":"SELECT 1"}
                        """)).andExpect(status().isBadRequest());
        verifyNoInteractions(approval);
    }

    @Test void validDeveloperRequestUsesAuthenticatedIdentity() throws Exception {
        when(approval.proposeQuery(any(), eq("dev"))).thenAnswer(i -> i.getArgument(0));
        mvc.perform(post("/api/v1/master/governance/propose").with(csrf())
                .with(user("dev").authorities(new SimpleGrantedAuthority("DEVELOPER")))
                .param("userId", "another-user").contentType("application/json").content(validBody()))
                .andExpect(status().isOk());
        verify(approval).proposeQuery(any(), eq("dev"));
    }

    private String validBody() { return "{\"uniqueName\":\"safe_query\",\"sqlText\":\"SELECT 1\"}"; }

    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean ApiClientRepository clients() { return mock(ApiClientRepository.class); }
        @Bean DoorsSecurityProperties properties() { return new DoorsSecurityProperties(); }
        @Bean JwtAuthenticationFilter jwt(UserRepository users) {
            return new JwtAuthenticationFilter(mock(JwtUtils.class), mock(AuthenticationEventPublisher.class), users);
        }
        @Bean SwaggerSessionAuthenticationFilter swagger() {
            return new SwaggerSessionAuthenticationFilter(mock(SwaggerSessionService.class));
        }
        @Bean ManagerPlaneFilter manager(UserRepository users) {
            var access = mock(ManagerPlaneAccess.class); when(access.isAllowed(any())).thenReturn(true);
            var policy = mock(PlaneRolePolicy.class); when(policy.isRoleAllowed(any(), any())).thenReturn(true);
            return new ManagerPlaneFilter(access, policy, users);
        }
        @Bean QueryApprovalService approval() { return mock(QueryApprovalService.class); }
        @Bean QueryGovernanceController controller(QueryApprovalService approval) {
            return new QueryGovernanceController(mock(MappingService.class), approval);
        }
    }
}

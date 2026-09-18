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

    @Test void subscriptionManagementRejectsApiUsersAndDevelopers() throws Exception {
        for (String role : java.util.List.of("APIUSER", "DEVELOPER", "EXTERNAL", "SECURITYADMIN")) {
            mvc.perform(get("/api/v1/master/api-subscriptions?from=2026-09-17&to=2026-09-17")
                    .with(user("account").authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/master/api-subscriptions/users/1/license").with(csrf())
                    .with(user("account").authorities(new SimpleGrantedAuthority(role)))
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/master/api-subscriptions/users/1/weekly-access").with(csrf())
                    .with(user("account").authorities(new SimpleGrantedAuthority(role)))
                    .contentType("application/json").content("{\"version\":0,\"restricted\":false,\"slots\":[]}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test void subscriberDashboardUsesAuthenticatedIdentityAndReadRequiresCsrf() throws Exception {
        var apiUser=user("subscriber").authorities(new SimpleGrantedAuthority("APIUSER"));
        mvc.perform(get("/api/v1/external/api-user/subscription?from=2026-09-17&to=2026-09-17&username=victim")
                .with(apiUser)).andExpect(status().isOk());
        verify(context.getBean(ApiSubscriberDashboardService.class)).dashboard(eq("subscriber"), any(), any());
        mvc.perform(post("/api/v1/external/api-user/subscription/notices/1/read").with(apiUser))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/external/api-user/subscription/notices/1/read").with(apiUser).with(csrf()))
                .andExpect(status().isOk());
        verify(context.getBean(ApiSubscriberDashboardService.class)).markRead(1L,"subscriber");
    }

    @Test void managerCanReadSubscriptionOverview() throws Exception {
        mvc.perform(get("/api/v1/master/api-subscriptions?from=2026-09-17&to=2026-09-17")
                .with(user("manager").authorities(new SimpleGrantedAuthority("DATAMANAGER"))))
                .andExpect(status().isOk());
    }
    @Test void apiUserCanReadInteractiveReportsButCannotManageSubscriptions() throws Exception {
        var subscriber=user("subscriber").authorities(new SimpleGrantedAuthority("APIUSER"));
        mvc.perform(get("/api/v1/reports/templates").with(subscriber)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/reports/templates")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/reports/execute").with(subscriber)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/reports/execute").with(subscriber).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/master/api-subscriptions?from=2026-09-17&to=2026-09-17").with(subscriber))
                .andExpect(status().isForbidden());
    }
    @Test void weeklyScheduleRequiresManagerCsrfAndValidWeekdays() throws Exception {
        var manager=user("manager").authorities(new SimpleGrantedAuthority("DATAMANAGER"));
        String path="/api/v1/master/api-subscriptions/users/1/weekly-access";
        String valid="{\"version\":0,\"restricted\":false,\"slots\":[]}";
        mvc.perform(put(path).with(manager).contentType("application/json").content(valid)).andExpect(status().isForbidden());
        mvc.perform(put(path).with(manager).with(csrf()).contentType("application/json").content(valid)).andExpect(status().isOk());
        mvc.perform(put(path).with(manager).with(csrf()).contentType("application/json")
                .content("{\"version\":0,\"restricted\":true,\"slots\":[{\"startDay\":8,\"startTime\":\"09:00\",\"endDay\":1,\"endTime\":\"18:00\"}]}"))
                .andExpect(status().isBadRequest());
    }
    @Test void subscriptionPeriodWritesAreManagerOnlyAndCsrfProtected() throws Exception {
        String path="/api/v1/master/api-subscriptions/users/1/periods";
        String valid="{\"validFrom\":\"2026-04-01\",\"validTo\":\"2027-03-31\",\"gepnicDue\":100,\"gepnicPaid\":0,\"doorsDue\":0,\"doorsPaid\":0,\"notes\":\"\",\"version\":0}";
        var manager=user("manager").authorities(new SimpleGrantedAuthority("DATAMANAGER"));
        var subscriber=user("subscriber").authorities(new SimpleGrantedAuthority("APIUSER"));
        mvc.perform(post(path).with(subscriber).with(csrf()).contentType("application/json").content(valid)).andExpect(status().isForbidden());
        mvc.perform(put(path+"/2").with(subscriber).with(csrf()).contentType("application/json").content(valid)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(manager).contentType("application/json").content(valid)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(manager).with(csrf()).contentType("application/json").content(valid)).andExpect(status().isOk());
        mvc.perform(post(path).with(manager).with(csrf()).contentType("application/json").content(valid.replace("\"gepnicDue\":100","\"gepnicDue\":-1"))).andExpect(status().isBadRequest());
    }

    @Test void viewerCannotUseDeveloperFunction() throws Exception {
        mvc.perform(post("/api/v1/master/governance/propose").with(csrf())
                .with(user("viewer").authorities(new SimpleGrantedAuthority("DATAVIEWER")))
                .contentType("application/json").content(validBody())).andExpect(status().isForbidden());
        verifyNoInteractions(approval);
    }

    @Test void developerCannotApproveGovernanceOrTemplateEvenWithCsrf() throws Exception {
        var developer = user("dev").authorities(new SimpleGrantedAuthority("DEVELOPER"));
        mvc.perform(post("/api/v1/governance/requests/999999999/approve")
                .with(csrf()).with(developer)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/master/templates/status/999999999")
                .with(csrf()).with(developer)
                .contentType("application/json").content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
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

    @Test void logoutRequiresCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").with(user("dev")))
                .andExpect(status().isForbidden());
    }

    @Test void logoutRejectsInvalidCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").with(user("dev")).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
    }

    @Test void logoutAcceptsValidCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").with(user("dev")).with(csrf().asHeader()))
                .andExpect(status().isNoContent());
    }

    @Test void airaChatIsRestrictedToManagerRolesAndCsrf() throws Exception {
        String path = "/api/v1/master/aira/chat";
        mvc.perform(post(path).with(csrf()).with(user("external")
                .authorities(new SimpleGrantedAuthority("EXTERNAL"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf()).with(user("developer")
                .authorities(new SimpleGrantedAuthority("DEVELOPER"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("manager")
                .authorities(new SimpleGrantedAuthority("DATAMANAGER"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf()).with(user("manager")
                .authorities(new SimpleGrantedAuthority("DATAMANAGER"))))
                .andExpect(status().isNoContent());
    }

    // Isolates the production filter chain from logout database side effects.
    @org.springframework.web.bind.annotation.RestController
    static class LogoutProbe {
        @org.springframework.web.bind.annotation.PostMapping("/api/v1/auth/logout")
        org.springframework.http.ResponseEntity<Void> logout() {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class AiraProbe {
        @org.springframework.web.bind.annotation.PostMapping("/api/v1/master/aira/chat")
        org.springframework.http.ResponseEntity<Void> chat() {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
    }
    @org.springframework.web.bind.annotation.RestController
    static class InteractiveReportProbe {
        @org.springframework.web.bind.annotation.GetMapping("/api/v1/reports/templates")
        org.springframework.http.ResponseEntity<Void> templates() { return org.springframework.http.ResponseEntity.noContent().build(); }
        @org.springframework.web.bind.annotation.PostMapping("/api/v1/reports/execute")
        org.springframework.http.ResponseEntity<Void> execute() { return org.springframework.http.ResponseEntity.noContent().build(); }
    }

    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean ApiSubscriptionService subscriptionService() { return mock(ApiSubscriptionService.class); }
        @Bean ApiSubscriberDashboardService subscriberDashboardService() {
            var service=mock(ApiSubscriberDashboardService.class);
            when(service.dashboard(any(), any(), any())).thenAnswer(i -> new java.util.LinkedHashMap<String,Object>());
            return service;
        }
        @Bean org.gepnic.doors.masterapi.controller.ApiSubscriberDashboardController subscriberDashboard(ApiSubscriberDashboardService service) {
            return new org.gepnic.doors.masterapi.controller.ApiSubscriberDashboardController(service);
        }
        @Bean org.gepnic.doors.masterapi.controller.ApiSubscriptionController subscriptions(ApiSubscriptionService service, ApiSubscriberDashboardService dashboard) {
            return new org.gepnic.doors.masterapi.controller.ApiSubscriptionController(service,dashboard);
        }
        @Bean LogoutProbe logoutProbe() { return new LogoutProbe(); }
        @Bean AiraProbe airaProbe() { return new AiraProbe(); }
        @Bean InteractiveReportProbe interactiveReportProbe() { return new InteractiveReportProbe(); }
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

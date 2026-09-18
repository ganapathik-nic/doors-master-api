package org.gepnic.doors.masterapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ApiEgressFilterTest {
    @Test void countsUtf8BodyWithoutChangingIt() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var filter=new ApiEgressFilter(jdbc);
        var request=new MockHttpServletRequest("POST","/api/v1/master/gateway/orchestrate/q");
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->{
            req.setAttribute(ApiSubscriptionInterceptor.CLIENT_ATTRIBUTE,7L);
            req.setAttribute("TOTAL_RECORD_COUNT",3);
            res.setCharacterEncoding("UTF-8");res.getWriter().write("नमस्ते");
        });
        assertEquals("नमस्ते",response.getContentAsString());
        verify(jdbc).update(anyString(),eq(7L),eq(request.getRequestURI()),eq(200),
                eq((long)"नमस्ते".getBytes(StandardCharsets.UTF_8).length),eq(3L),anyLong(),eq(true));
    }
    @Test void streamIsCountedOnceAfterAsyncCompletion() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var filter=new ApiEgressFilter(jdbc);
        var request=new MockHttpServletRequest("POST","/api/v1/master/gateway/documents/s/download");
        request.setAsyncSupported(true);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->{
            req.setAttribute(ApiSubscriptionInterceptor.CLIENT_ATTRIBUTE,7L);
            req.startAsync(req,res);
        });
        verifyNoInteractions(jdbc);
        var context=request.getAsyncContext();
        context.getResponse().getOutputStream().write(new byte[2048]);
        context.complete();
        assertEquals(2048,response.getContentAsByteArray().length);
        verify(jdbc,times(1)).update(anyString(),eq(7L),eq(request.getRequestURI()),eq(200),eq(2048L),eq(0L),anyLong(),eq(true));
    }
    @Test void portalReportsAreNotMetered() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        new ApiEgressFilter(jdbc).doFilter(new MockHttpServletRequest("POST","/api/v1/reports/execute"),new MockHttpServletResponse(),(req,res)->res.getWriter().write("report"));
        verifyNoInteractions(jdbc);
    }
    @Test void fastFailedStreamIsRecordedOnceAsIncomplete() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var filter=new ApiEgressFilter(jdbc);
        var request=new MockHttpServletRequest("POST","/api/v1/master/gateway/documents/s/download");
        request.setAsyncSupported(true);
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{
            req.setAttribute(ApiSubscriptionInterceptor.CLIENT_ATTRIBUTE,7L);
            var context=(MockAsyncContext)req.startAsync(req,res);
            res.getOutputStream().write(new byte[15]);
            for(var listener:context.getListeners()) listener.onError(new jakarta.servlet.AsyncEvent(context));
            context.complete();
        });
        verify(jdbc,times(1)).update(anyString(),eq(7L),eq(request.getRequestURI()),eq(200),eq(15L),eq(0L),anyLong(),eq(false));
    }
}

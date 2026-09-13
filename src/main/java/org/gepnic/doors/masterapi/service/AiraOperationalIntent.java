package org.gepnic.doors.masterapi.service;

import java.util.Locale;

/** Deterministic routing for the small, governed set of operational metrics. */
record AiraOperationalIntent(Source source, Domain domain, Metric metric, String status, Period period) {
    enum Source { OPERATIONAL, DOCUMENTATION, MIXED }
    enum Domain { QUERIES, AGENTS, USERS, DATA_REQUESTS, API_CLIENTS, EXECUTIONS, DOCUMENT_DOWNLOADS, INSIGHTS, PLATFORM }
    enum Metric { COUNT, BREAKDOWN, SUCCESS_RATE, RECENT_ACTIVITY }
    enum Period { TODAY, SEVEN_DAYS, THIRTY_DAYS, ALL_TIME }

    static AiraOperationalIntent classify(String prompt) {
        String p = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        Domain domain = domain(p);
        boolean operationalLanguage = p.contains("how many") || p.contains("count") || p.contains("any ")
                || p.contains("currently") || p.contains("current ") || p.contains("live ")
                || p.contains("status") || p.contains("usage") || p.contains("success rate")
                || p.contains("failed today") || p.contains("recent activity") || p.contains("insight")
                || p.contains("needs attention") || p.contains("overview") || p.contains("health summary");
        boolean procedureLanguage = p.contains("how do") || p.contains("how does") || p.contains("procedure")
                || p.contains("process") || p.contains("who approve") || p.contains("sop")
                || p.contains("explain") || p.contains("policy");
        Source source = domain != null && operationalLanguage
                ? (procedureLanguage ? Source.MIXED : Source.OPERATIONAL)
                : Source.DOCUMENTATION;
        Metric metric = p.contains("breakdown") || p.contains("by status") ? Metric.BREAKDOWN
                : p.contains("success rate") || p.contains("failure rate") ? Metric.SUCCESS_RATE
                : p.contains("recent activity") ? Metric.RECENT_ACTIVITY : Metric.COUNT;
        Period period = p.contains("today") ? Period.TODAY : p.contains("30 day") ? Period.THIRTY_DAYS
                : p.contains("7 day") || p.contains("week") ? Period.SEVEN_DAYS : Period.ALL_TIME;
        return new AiraOperationalIntent(source, domain == null ? Domain.PLATFORM : domain, metric,
                status(p), period);
    }

    private static Domain domain(String p) {
        if (p.contains("insight") || p.contains("needs attention") || p.contains("attention today")
                || p.contains("operational overview") || p.contains("usage overview")
                || p.contains("health summary") || p.contains("operational priorities")
                || (p.contains("summar") && (p.contains("usage") || p.contains("operational")
                || p.contains("current doors")))) return Domain.INSIGHTS;
        if ((p.contains("document") || p.contains("file"))
                && (p.contains("download") || p.contains("stream") || p.contains("receipt")
                || p.contains("checksum"))) return Domain.DOCUMENT_DOWNLOADS;
        if (p.contains("quer") || p.contains("report catalogue") || p.contains("template")) return Domain.QUERIES;
        if (p.contains("agent")) return Domain.AGENTS;
        if (p.contains("user")) return Domain.USERS;
        if (p.contains("data request") || p.contains("request")) return Domain.DATA_REQUESTS;
        if (p.contains("api client") || p.contains("client")) return Domain.API_CLIENTS;
        if (p.contains("execution") || p.contains("usage") || p.contains("failure") || p.contains("success")) return Domain.EXECUTIONS;
        if (p.contains("system status") || p.contains("platform status") || p.contains("operational status")) return Domain.PLATFORM;
        return null;
    }

    private static String status(String p) {
        if (p.contains("pending") || p.contains("awaiting") || p.contains("submitted")) return "PENDING";
        if (p.contains("approved")) return "APPROVED";
        if (p.contains("rejected")) return "REJECTED";
        if (p.contains("inactive")) return "INACTIVE";
        if (p.contains("failed") || p.contains("failure")) return "FAILED";
        if (p.contains("verified")) return "VERIFIED";
        if (p.contains("mismatch")) return "MISMATCHED";
        if (p.contains("successful") || p.contains("success")) return "SUCCESS";
        if (p.contains("active")) return "ACTIVE";
        return null;
    }
}

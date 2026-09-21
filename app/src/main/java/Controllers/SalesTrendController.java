package Controllers;

import Services.SalesTrendService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Date;
import java.util.List;

@Path("/api/sales-trend")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SalesTrendController {

    @Inject
    @Nonnull
    SalesTrendService salesTrendService;

    @GET
    @Path("/daily")
    @Nonnull
    public Response getDailyTimeSeries(
            @Nullable @QueryParam("startDate") Long startDate,
            @Nullable @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            List<SalesTrendService.TimeSeriesData> results = salesTrendService.getDailySalesTimeSeries(start, end);
            return Response.ok(results).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/weekly")
    @Nonnull
    public Response getWeeklyTimeSeries(
            @Nullable @QueryParam("startDate") Long startDate,
            @Nullable @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            List<SalesTrendService.TimeSeriesData> results = salesTrendService.getWeeklySalesTimeSeries(start, end);
            return Response.ok(results).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/monthly")
    @Nonnull
    public Response getMonthlyTimeSeries(
            @Nullable @QueryParam("startDate") Long startDate,
            @Nullable @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 730L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            List<SalesTrendService.TimeSeriesData> results = salesTrendService.getMonthlySalesTimeSeries(start, end);
            return Response.ok(results).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/seasonal/{year}")
    @Nonnull
    public Response getSeasonalPattern(@PathParam("year") int year) {
        try {
            SalesTrendService.SeasonalPattern result = salesTrendService.getSeasonalPattern(year);
            return Response.ok(result).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/year-over-year")
    @Nonnull
    public Response getYearOverYearComparison(
            @QueryParam("currentYear") @DefaultValue("2026") int currentYear,
            @QueryParam("yearsBack") @DefaultValue("3") int yearsBack) {
        try {
            SalesTrendService.YearOverYearComparison result = salesTrendService.getYearOverYearComparison(currentYear, yearsBack);
            return Response.ok(result).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/trend-indicators")
    @Nonnull
    public Response getTrendIndicators(
            @Nullable @QueryParam("startDate") Long startDate,
            @Nullable @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            SalesTrendService.TrendIndicators result = salesTrendService.getTrendIndicators(start, end);
            return Response.ok(result).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/heatmap")
    @Nonnull
    public Response getHourlyHeatmap(
            @Nullable @QueryParam("startDate") Long startDate,
            @Nullable @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            List<SalesTrendService.HourlyHeatmap> results = salesTrendService.getHourlyHeatmap(start, end);
            return Response.ok(results).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/metrics")
    @Nonnull
    public Response getGrowthMetrics(
            @Nullable @QueryParam("startDate") Long startDate,
            @Nullable @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            SalesTrendService.GrowthMetrics result = salesTrendService.getGrowthMetrics(start, end);
            return Response.ok(result).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }
}

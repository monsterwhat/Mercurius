package Controllers;

import Services.DashboardMetricsService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;

@Path("/api/dashboard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DashboardMetricsController {

    @Inject @Nonnull
    DashboardMetricsService dashboardMetricsService;

    @GET
    @Path("/kpis")
    @Nonnull
    public Response getKPIs(@QueryParam("username") @Nullable String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            DashboardMetricsService.DashboardKPI kpis = dashboardMetricsService.getKPIs(user);
            return Response.ok(kpis).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/today-sales")
    @Nonnull
    public Response getTodaySales(@QueryParam("username") @Nullable String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getTodaySales(user)).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/yesterday-sales")
    @Nonnull
    public Response getYesterdaySales(@QueryParam("username") @Nullable String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getYesterdaySales(user)).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/week-sales")
    @Nonnull
    public Response getWeekSales(@QueryParam("username") @Nullable String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getWeekSales(user)).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/month-sales")
    @Nonnull
    public Response getMonthSales(@QueryParam("username") @Nullable String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getMonthSales(user)).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/today-transactions")
    @Nonnull
    public Response getTodayTransactions(@QueryParam("username") @Nullable String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getTodayTransactions(user)).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/average-ticket")
    @Nonnull
    public Response getAverageTicket(
            @QueryParam("username") @Nullable String username,
            @QueryParam("days") @DefaultValue("30") int days) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getAverageTicket(user, days)).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/top-products")
    @Nonnull
    public Response getTopSellingProducts(
            @QueryParam("username") @Nullable String username,
            @QueryParam("limit") @DefaultValue("10") int limit) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            List<DashboardMetricsService.TopProduct> products = dashboardMetricsService.getTopSellingProducts(user, limit);
            return Response.ok(products).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/hourly-distribution")
    @Nonnull
    public Response getHourlySalesDistribution(
            @QueryParam("username") @Nullable String username,
            @QueryParam("date") @Nullable String dateStr) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            LocalDate date = dateStr != null ? LocalDate.parse(dateStr) : LocalDate.now();
            List<DashboardMetricsService.HourlySales> hourlySales = dashboardMetricsService.getHourlySalesDistribution(user, date);
            return Response.ok(hourlySales).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/weekly-breakdown")
    @Nonnull
    public Response getWeeklySalesBreakdown(@QueryParam("username") @Nullable String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            List<DashboardMetricsService.DailySales> weeklySales = dashboardMetricsService.getWeeklySalesBreakdown(user);
            return Response.ok(weeklySales).build();
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }
}

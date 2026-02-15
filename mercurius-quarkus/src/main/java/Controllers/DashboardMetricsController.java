package Controllers;

import Services.DashboardMetricsService;
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

    @Inject
    DashboardMetricsService dashboardMetricsService;

    @GET
    @Path("/kpis")
    public Response getKPIs(@QueryParam("username") String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            DashboardMetricsService.DashboardKPI kpis = dashboardMetricsService.getKPIs(user);
            return Response.ok(kpis).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/today-sales")
    public Response getTodaySales(@QueryParam("username") String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getTodaySales(user)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/yesterday-sales")
    public Response getYesterdaySales(@QueryParam("username") String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getYesterdaySales(user)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/week-sales")
    public Response getWeekSales(@QueryParam("username") String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getWeekSales(user)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/month-sales")
    public Response getMonthSales(@QueryParam("username") String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getMonthSales(user)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/today-transactions")
    public Response getTodayTransactions(@QueryParam("username") String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getTodayTransactions(user)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/average-ticket")
    public Response getAverageTicket(
            @QueryParam("username") String username,
            @QueryParam("days") @DefaultValue("30") int days) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            return Response.ok(dashboardMetricsService.getAverageTicket(user, days)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/top-products")
    public Response getTopSellingProducts(
            @QueryParam("username") String username,
            @QueryParam("limit") @DefaultValue("10") int limit) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            List<DashboardMetricsService.TopProduct> products = dashboardMetricsService.getTopSellingProducts(user, limit);
            return Response.ok(products).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/hourly-distribution")
    public Response getHourlySalesDistribution(
            @QueryParam("username") String username,
            @QueryParam("date") String dateStr) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            LocalDate date = dateStr != null ? LocalDate.parse(dateStr) : LocalDate.now();
            List<DashboardMetricsService.HourlySales> hourlySales = dashboardMetricsService.getHourlySalesDistribution(user, date);
            return Response.ok(hourlySales).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/weekly-breakdown")
    public Response getWeeklySalesBreakdown(@QueryParam("username") String username) {
        try {
            Models.Users user = new Models.Users();
            user.setUsername(username);
            List<DashboardMetricsService.DailySales> weeklySales = dashboardMetricsService.getWeeklySalesBreakdown(user);
            return Response.ok(weeklySales).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }
}

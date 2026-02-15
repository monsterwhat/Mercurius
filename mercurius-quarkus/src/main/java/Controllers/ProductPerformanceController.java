package Controllers;

import Services.ProductPerformanceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Path("/api/product-performance")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductPerformanceController {

    @Inject
    ProductPerformanceService productPerformanceService;

    @GET
    @Path("/best-selling")
    public Response getBestSellingProducts(
            @QueryParam("startDate") Long startDate,
            @QueryParam("endDate") Long endDate,
            @QueryParam("limit") @DefaultValue("10") int limit) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            List<ProductPerformanceService.ProductSalesSummary> results = 
                productPerformanceService.getBestSellingProducts(start, end, limit);
            return Response.ok(results).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/worst-selling")
    public Response getWorstSellingProducts(
            @QueryParam("startDate") Long startDate,
            @QueryParam("endDate") Long endDate,
            @QueryParam("limit") @DefaultValue("10") int limit) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            List<ProductPerformanceService.ProductSalesSummary> results = 
                productPerformanceService.getWorstSellingProducts(start, end, limit);
            return Response.ok(results).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/best-by-revenue")
    public Response getBestSellingProductsByRevenue(
            @QueryParam("startDate") Long startDate,
            @QueryParam("endDate") Long endDate,
            @QueryParam("limit") @DefaultValue("10") int limit) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            List<ProductPerformanceService.ProductSalesSummary> results = 
                productPerformanceService.getBestSellingProductsByRevenue(start, end, limit);
            return Response.ok(results).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/velocity/{articuloId}")
    public Response getProductVelocity(
            @PathParam("articuloId") Long articuloId,
            @QueryParam("days") @DefaultValue("30") int days) {
        try {
            BigDecimal velocity = productPerformanceService.getProductVelocity(articuloId, days);
            return Response.ok(velocity).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/category-performance")
    public Response getCategoryPerformance(
            @QueryParam("startDate") Long startDate,
            @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            Map<String, BigDecimal> results = productPerformanceService.getCategoryPerformance(start, end);
            return Response.ok(results).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/department-performance")
    public Response getDepartmentPerformance(
            @QueryParam("startDate") Long startDate,
            @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            Map<String, BigDecimal> results = productPerformanceService.getDepartmentPerformance(start, end);
            return Response.ok(results).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/sales-trend")
    public Response getSalesTrend(
            @QueryParam("startDate") Long startDate,
            @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            List<ProductPerformanceService.DailySalesTrend> results = productPerformanceService.getSalesTrend(start, end);
            return Response.ok(results).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/abc-analysis")
    public Response performABCAnalysis(
            @QueryParam("startDate") Long startDate,
            @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            List<ProductPerformanceService.ABCAnalysis> results = productPerformanceService.performABCAnalysis(start, end);
            return Response.ok(results).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/summary")
    public Response getPerformanceSummary(
            @QueryParam("startDate") Long startDate,
            @QueryParam("endDate") Long endDate) {
        try {
            Date start = new Date(startDate != null ? startDate : System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
            Date end = new Date(endDate != null ? endDate : System.currentTimeMillis());
            ProductPerformanceService.ProductPerformanceSummary summary = 
                productPerformanceService.getPerformanceSummary(start, end);
            return Response.ok(summary).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }
}

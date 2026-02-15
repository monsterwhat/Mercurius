package Controllers;

import Services.StockForecastService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/stock-forecast")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StockForecastController {

    @Inject
    StockForecastService stockForecastService;

    @GET
    @Path("/forecast/{articuloId}")
    public Response generateForecast(
            @PathParam("articuloId") Long articuloId,
            @QueryParam("days") @DefaultValue("30") int days) {
        try {
            List<StockForecastService.ProductForecast> forecast = 
                stockForecastService.generateForecast(articuloId, days);
            return Response.ok(forecast).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/bulk-forecast")
    public Response generateBulkForecast(
            @QueryParam("days") @DefaultValue("30") int days) {
        try {
            List<StockForecastService.ProductForecast> forecast = 
                stockForecastService.generateBulkForecast(days);
            return Response.ok(forecast).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/demand/{articuloId}")
    public Response predictDemand(
            @PathParam("articuloId") Long articuloId,
            @QueryParam("days") @DefaultValue("30") int days) {
        try {
            StockForecastService.DemandPrediction prediction = 
                stockForecastService.predictDemand(articuloId, days);
            return Response.ok(prediction).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/health")
    public Response getInventoryHealth() {
        try {
            StockForecastService.InventoryHealthReport report = 
                stockForecastService.getInventoryHealthReport();
            return Response.ok(report).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }

    @GET
    @Path("/reorder/{articuloId}")
    public Response getReorderRecommendation(@PathParam("articuloId") Long articuloId) {
        try {
            StockForecastService.ReorderRecommendation recommendation = 
                stockForecastService.getReorderRecommendation(articuloId);
            return Response.ok(recommendation).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        }
    }
}

package com.zaza.housing.config;

import com.zaza.housing.controller.ApplicationReviewController;
import com.zaza.housing.controller.ConversationController;
import com.zaza.housing.controller.ConversationModerationController;
import com.zaza.housing.controller.DocumentController;
import com.zaza.housing.controller.LeaseBillingController;
import com.zaza.housing.controller.LeaseManagementController;
import com.zaza.housing.controller.MaintenanceController;
import com.zaza.housing.controller.NotificationController;
import com.zaza.housing.controller.ProviderJobController;
import com.zaza.housing.controller.ProviderPayoutController;
import com.zaza.housing.controller.ServiceFinanceAdminController;
import com.zaza.housing.controller.ServicePaymentController;
import com.zaza.housing.controller.ServiceProviderController;
import com.zaza.housing.controller.TenantApplicationController;
import com.zaza.housing.controller.TenantBillingController;
import com.zaza.housing.controller.TenantLeaseController;
import com.zaza.housing.controller.TenantProfileController;
import com.zaza.housing.controller.TenantWorkRequestController;
import com.zaza.housing.controller.VerificationAdminController;
import com.zaza.housing.controller.VerificationController;
import com.zaza.housing.controller.ViewingController;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(
    assignableTypes = {
      com.zaza.housing.controller.AgencyCrmController.class,
      com.zaza.housing.controller.AgencyFinanceController.class,
      com.zaza.housing.controller.AgencyMandateController.class,
      com.zaza.housing.controller.CaretakerWorkspaceController.class,
      com.zaza.housing.controller.PropertyTaskController.class,
      DocumentController.class,
      VerificationController.class,
      com.zaza.housing.controller.PropertyOwnershipVerificationController.class,
      VerificationAdminController.class,
      TenantProfileController.class,
      TenantApplicationController.class,
      ApplicationReviewController.class,
      NotificationController.class,
      TenantLeaseController.class,
      LeaseManagementController.class,
      LeaseBillingController.class,
      TenantBillingController.class,
      TenantWorkRequestController.class,
      MaintenanceController.class,
      ProviderJobController.class,
      ServiceProviderController.class,
      ServicePaymentController.class,
      ProviderPayoutController.class,
      ServiceFinanceAdminController.class,
      ConversationController.class,
      ViewingController.class,
      ConversationModerationController.class
    })
public class PrivateWorkflowResponseAdvice implements ResponseBodyAdvice<Object> {
  @Override
  public boolean supports(
      MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter method,
      MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    response.getHeaders().setCacheControl("no-store");
    return body;
  }
}

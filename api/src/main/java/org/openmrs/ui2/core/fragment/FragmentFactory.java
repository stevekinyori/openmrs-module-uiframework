package org.openmrs.ui2.core.fragment;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.openmrs.api.APIAuthenticationException;
import org.openmrs.ui2.core.RequestValidationException;
import org.openmrs.ui2.core.UiFrameworkException;
import org.openmrs.ui2.core.UiFrameworkUtil;
import org.openmrs.ui2.core.UiUtils;
import org.openmrs.ui2.core.extension.ExtensionManager;
import org.openmrs.ui2.core.fragment.action.FailureResult;
import org.openmrs.ui2.core.page.PageAction;
import org.openmrs.ui2.core.page.PageContext;
import org.openmrs.ui2.core.page.PageModel;
import org.openmrs.ui2.core.page.PageRequest;
import org.openmrs.ui2.core.page.Redirect;
import org.openmrs.ui2.core.session.Session;
import org.openmrs.ui2.core.session.SessionFactory;
import org.openmrs.ui2.util.ExceptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.core.convert.ConversionService;

/**
 * Handles FragmentRequests
 */
public class FragmentFactory {
	
	protected final Logger log = LoggerFactory.getLogger(getClass());
	
	@Autowired
	ApplicationContext applicationContext;
	
	@Autowired
	ConversionService conversionService;
	
	@Autowired
	MessageSource messageSource;
	
	@Autowired
	ExtensionManager extensionManager;
	
	@Autowired
	SessionFactory sessionFactory;
	
	@Autowired(required=false)
	ServletContext servletContext;
	
	private boolean developmentMode = false;
	
	private static Map<String, FragmentControllerProvider> controllerProviders;
	
	private static Map<String, FragmentViewProvider> viewProviders;
	
	// a cache of views for production mode
	private Map<String, FragmentView> viewCache = new HashMap<String, FragmentView>();
	
	// a singleton one of these that can be reused
	private EmptyFragmentController emptyController = new EmptyFragmentController();
	
	public FragmentFactory() {
	}
	
	public String process(FragmentContext context) throws PageAction {
		if (context.getRequestDepth() > 100)
			throw new UiFrameworkException("Fragment inclusion > 100 levels deep. Check your code for infinite loops.");
		long startTime = System.currentTimeMillis();
		log.info("processing " + context.getRequest().getId());
		applyDefaultConfiguration(context);
		// it's possible someone has pre-requested that this fragment be decorated
		if (context.getRequest().getConfiguration().containsKey("decorator")) {
			String decoratorName = "decorators/" + context.getRequest().getConfiguration().get("decorator");
			@SuppressWarnings("unchecked")
			Map<String, Object> decoratorConfigurationMap = (Map<String, Object>) context.getRequest().getConfiguration()
			        .get("decoratorConfig");
			FragmentConfiguration decoratorConfiguration = new FragmentConfiguration(decoratorConfigurationMap);
			FragmentRequest decorator = new FragmentRequest(decoratorName, decoratorConfiguration);
			context.setDecorateWith(decorator);
		}
		String result = processThisFragment(context);
		if (context.getDecorateWith() == null)
			return result;
		FragmentRequest decoratorRequest = context.getDecorateWith();
		decoratorRequest.getConfiguration().put("content", result);
		decoratorRequest.getConfiguration().put("contentFragmentId", context.getRequest().getConfiguration().get("id"));
		FragmentContext decoratorContext = new FragmentContext(decoratorRequest, context);
		String ret = process(decoratorContext);
		log.info("\thandled " + context.getRequest().getId() + " in " + (System.currentTimeMillis() - startTime) + " ms");
		return ret;
	}
	
	private void applyDefaultConfiguration(FragmentContext context) {
		FragmentConfiguration config = context.getRequest().getConfiguration();
		if (!config.containsKey("id"))
			config.put("id", UiUtils.randomId("fr"));
	}
	
	private String processThisFragment(FragmentContext context) throws PageAction {
		// determine what controller to use
		Object controller = getController(context.getRequest());
		if (controller == null)
			controller = emptyController;
		context.setController(controller);
		
		// let the controller handle the request
		Object result = handleRequestWithController(context);
		
		// if the return type is a FragmentRequest, that means we "redirect" to that fragment request instead
		if (result instanceof FragmentRequest) {
			FragmentRequest replacement = (FragmentRequest) result;
			avoidDuplicateDecoration(replacement, context.getRequest());
			FragmentContext replacementContext = new FragmentContext(replacement, context);
			return process(replacementContext);
		}
		
		// otherwise, the result must be a String
		String resultString = (String) result;
		
		// fragments are allowed to redirect to other pages
		if (resultString != null && resultString.startsWith("redirect:")) {
			String toApplicationUrl = resultString.substring("redirect:".length());
			throw new Redirect(toApplicationUrl);
		}
		
		// determine what view to use
		String viewName = resultString;
		FragmentView view = getView(context.getRequest(), viewName);
		context.setView(view);
		
		if (context.getController().equals(emptyController) && context.getView() == null) {
			throw new RuntimeException("Cannot find controller or view for fragment: " + context.getRequest().getId());
		}
		
		// Fragments are allowed to have no view (their controller can still affect the shared
		// page model, do redirects, etc) but if we have neither a controller nor a view, that's bad
		if (view == null) {
			return "";
		}
		
		// render the output
		String output = view.render(context);
		return output;
	}
	
	/**
	 * If one {@link FragmentRequest} "redirects" to another (by having its controller method return
	 * a new FragmentRequest) and the FragmentRequest that it returns includes exactly the same
	 * configuration as the original request, we need to remove the "decorator" attribute from the
	 * replacement configuration because the fragmework is already applying that decoration.
	 * 
	 * @param replacement
	 * @param original
	 */
	private void avoidDuplicateDecoration(FragmentRequest replacement, FragmentRequest original) {
		if (replacement.getConfiguration() != null && replacement.getConfiguration() == original.getConfiguration()) {
			FragmentConfiguration newConfig = new FragmentConfiguration(replacement.getConfiguration());
			newConfig.remove("decorator");
			replacement.setConfiguration(newConfig);
		}
	}
	
	// if you change the supported parameter classes, make sure to update the documentation on the wiki
	private Object handleRequestWithController(FragmentContext context) throws PageAction {
		Map<Class<?>, Object> possibleArguments = new LinkedHashMap<Class<?>, Object>();
		possibleArguments.put(FragmentContext.class, context);
		possibleArguments.put(PageContext.class, context.getPageContext());
		possibleArguments.put(FragmentConfiguration.class, context.getRequest().getConfiguration());
		possibleArguments.put(FragmentModel.class, context.getModel());
		possibleArguments.put(PageModel.class, context.getPageContext().getModel());
		possibleArguments.put(FragmentRequest.class, context.getRequest());
		possibleArguments.put(PageRequest.class, context.getPageContext().getRequest());
		possibleArguments.put(HttpServletRequest.class, context.getPageContext().getRequest().getRequest());
		possibleArguments.put(FragmentFactory.class, this);
		possibleArguments.put(UiUtils.class, new FragmentUiUtils(context));
		possibleArguments.put(Session.class, context.getPageContext().getRequest().getSession());
		possibleArguments.put(ApplicationContext.class, applicationContext);
		possibleArguments.put(ServletContext.class, servletContext);
		return UiFrameworkUtil.executeControllerMethod(context.getController(), possibleArguments, conversionService);
	}
	
	/**
	 * @param request
	 * @param viewName
	 * @return
	 */
	private FragmentView getView(FragmentRequest request, String viewName) {
		if (viewName == null)
			viewName = request.getId();
		if (!isDevelopmentMode()) {
			if (viewCache.containsKey(viewName))
				return viewCache.get(viewName);
		}
		for (FragmentViewProvider p : viewProviders.values()) {
			FragmentView ret = p.getView(viewName);
			if (ret != null) {
				if (!isDevelopmentMode())
					viewCache.put(viewName, ret);
				return ret;
			}
		}
		return null;
	}
	
	public Object getController(String fragmentName) {
		FragmentRequest request = new FragmentRequest(fragmentName);
		return getController(request);
	}
	
	/**
	 * TODO cache the results in production mode?
	 * 
	 * @param request
	 * @return
	 */
	private Object getController(FragmentRequest request) {
		if (controllerProviders != null) {
			for (FragmentControllerProvider p : controllerProviders.values()) {
				Object ret = p.getController(request.getId());
				if (ret != null)
					return ret;
			}
		}
		return null;
	}
	
	/**
	 * @return the developmentMode
	 */
	public boolean isDevelopmentMode() {
		return developmentMode;
	}
	
	/**
	 * @param developmentMode the developmentMode to set
	 */
	public void setDevelopmentMode(boolean developmentMode) {
		this.developmentMode = developmentMode;
	}
	
	/**
	 * @return the controllerProviders
	 */
	public Map<String, FragmentControllerProvider> getControllerProviders() {
		return controllerProviders;
	}
	
	/**
	 * @param controllerProviders the controllerProviders to set
	 */
	public void setControllerProviders(Map<String, FragmentControllerProvider> newControllerProviders) {
		controllerProviders = newControllerProviders;
	}
	
	public void setAdditionalControllerProviders(Map<String, FragmentControllerProvider> additional) {
		if (controllerProviders == null)
			controllerProviders = new LinkedHashMap<String, FragmentControllerProvider>();
		controllerProviders.putAll(additional);
	}
	
	/**
	 * @return the viewProviders
	 */
	public Map<String, FragmentViewProvider> getViewProviders() {
		return viewProviders;
	}
	
	/**
	 * @param viewProviders the viewProviders to set
	 */
	public void setViewProviders(Map<String, FragmentViewProvider> newViewProviders) {
		viewProviders = newViewProviders;
	}
	
	public void setAdditionalViewProviders(Map<String, FragmentViewProvider> additional) {
		if (viewProviders == null)
			viewProviders = new LinkedHashMap<String, FragmentViewProvider>();
		viewProviders.putAll(additional);
	}
	
	public Object invokeFragmentAction(String fragmentName, String action, HttpServletRequest httpRequest) {
		log.info("Invoking " + fragmentName + " . " + action);
		FragmentActionRequest request = new FragmentActionRequest(this, httpRequest);
		
		// try to find the requested fragment controller
		Object controller = getController(fragmentName);
		if (controller == null) {
			throw new UiFrameworkException("Cannot find fragment controller for " + fragmentName);
		}
		
		// find the correct action method
		Method method = null;
		for (Method candidate : controller.getClass().getMethods()) {
			if (candidate.getName().equals(action)) {
				method = candidate;
				break;
			}
		}
		if (method == null) {
			throw new UiFrameworkException("Error getting " + controller.getClass() + "." + action + " method");
		}
		
		// determine method arguments
		Map<Class<?>, Object> possibleArguments = new LinkedHashMap<Class<?>, Object>();
		possibleArguments.put(FragmentActionRequest.class, request);
		possibleArguments.put(HttpServletRequest.class, httpRequest);
		possibleArguments.put(UiUtils.class, request.getUiUtils());
		possibleArguments.put(Session.class, sessionFactory.getSession(httpRequest.getSession()));
		possibleArguments.put(ApplicationContext.class, applicationContext);
		possibleArguments.put(ServletContext.class, servletContext);
		Object[] params = null;
		try {
			params = UiFrameworkUtil.determineControllerMethodParameters(method, possibleArguments, conversionService);
		}
		catch (RequestValidationException ex) {
			for (String errorCode : ex.getGlobalErrorCodes())
				request.getErrors().reject(errorCode);
			for (Map.Entry<String, List<String>> e : ex.getFieldErrorCodes().entrySet()) {
				for (String errorCode : e.getValue())
					request.getErrors().rejectValue(e.getKey(), errorCode);
			}
		}
		
		if (request.hasErrors())
			return new FailureResult(request.getErrors());
		
		// invoke method
		Object result;
		try {
			result = method.invoke(controller, params);
		}
		catch (Exception ex) {
			// it's possible that the underlying exception is that the user was logged out or lacks privileges
			// and we want to special-case that
			APIAuthenticationException authEx = ExceptionUtil.findExceptionInChain(ex, APIAuthenticationException.class);
			if (authEx != null)
				throw authEx;
			
			// we don't know how to handle other types of exceptions
			log.error("error", ex);
			throw new UiFrameworkException("Error invoking fragment action " + method, ex);
		}
		
		return result;
	}
	
	public boolean fragmentExists(String fragmentName) {
		Object controller = getController(fragmentName);
		if (controller != null) {
			return true;
		}
		
		FragmentView view = getView(new FragmentRequest(fragmentName), fragmentName);
		return (view != null);
	}
	
	public <T> T convert(Object obs, Class<T> toType) {
		return conversionService.convert(obs, toType);
	}
	
	public <T> Map<String, T> getSpringBeansOfType(Class<T> ofType) {
		return applicationContext.getBeansOfType(ofType);
	}
	
	public MessageSource getMessageSource() {
		return messageSource;
	}
	
	public ExtensionManager getExtensionManager() {
		return extensionManager;
	}
	
	public ConversionService getConversionService() {
		return conversionService;
	}
	
	public ServletContext getServletContext() {
		return servletContext;
	}
	
}

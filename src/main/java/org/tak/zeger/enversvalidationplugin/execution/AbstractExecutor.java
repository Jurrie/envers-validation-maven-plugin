package org.tak.zeger.enversvalidationplugin.execution;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.apache.maven.plugin.logging.Log;
import org.tak.zeger.enversvalidationplugin.annotation.ConnectionProvider;
import org.tak.zeger.enversvalidationplugin.annotation.Parameterized;
import org.tak.zeger.enversvalidationplugin.annotation.TargetPhase;
import org.tak.zeger.enversvalidationplugin.annotation.Validate;
import org.tak.zeger.enversvalidationplugin.annotation.WhiteList;
import org.tak.zeger.enversvalidationplugin.connection.ConnectionProviderInstance;
import org.tak.zeger.enversvalidationplugin.entities.AuditTableInformation;
import org.tak.zeger.enversvalidationplugin.entities.ValidationResults;
import org.tak.zeger.enversvalidationplugin.exceptions.SetupValidationForSpecificWhitelistEntryException;
import org.tak.zeger.enversvalidationplugin.exceptions.ValidationException;
import org.tak.zeger.enversvalidationplugin.utils.IgnoreUtils;
import org.tak.zeger.enversvalidationplugin.utils.ReflectionUtils;

abstract class AbstractExecutor
{
	private final ConnectionProviderInstance connectionProvider;
	private final Log log;
	private final List<String> ignorables;

	AbstractExecutor(@Nonnull ConnectionProviderInstance connectionProvider, @Nonnull Log log, @Nonnull List<String> ignorables)
	{
		this.connectionProvider = connectionProvider;
		this.log = log;
		this.ignorables = ignorables;
	}

	@Nonnull
	private List<ValidatorWrapper> createValidatorInstances(@Nonnull Class<?> validatorClass, @Nonnull Map<String, AuditTableInformation> whitelist, @Nonnull ValidationResults validationResults)
	{
		try
		{
			final List<Method> validateMethods = new ArrayList<>();
			Method parameterizedMethod = null;

			for (Method method : validatorClass.getMethods())
			{
				final Validate validate = method.getAnnotation(Validate.class);
				if (validate != null)
				{
					validateMethods.add(method);
					continue;
				}

				final Parameterized parameterized = method.getAnnotation(Parameterized.class);
				if (parameterized != null && Modifier.isStatic(method.getModifiers()))
				{
					if (parameterizedMethod == null)
					{
						parameterizedMethod = method;
					}
					else
					{
						throw new ValidationException(validatorClass.getCanonicalName() + " could not be used for validating, as the class defines more than one " + Parameterized.class.getSimpleName() + " annotated methods.");
					}
				}
			}

			if (parameterizedMethod == null)
			{
				final Object[] constructorArguments = {};
				final Object validatorInstance = createValidatorInstance(validatorClass, constructorArguments, whitelist);
				return Collections.singletonList(new SingleValidatorWrapper(validatorInstance, validateMethods));
			}
			else
			{
				try
				{
					final List<Object[]> generatedData = generateDataForConstructorArguments(parameterizedMethod, whitelist);
					final List<ValidatorWrapper> validatorInstances = new ArrayList<>(generatedData.size());
					for (int index = 0; index < generatedData.size(); index++)
					{
						final Object[] constructorArguments = generatedData.get(index);
						final Object validatorInstance = createValidatorInstance(validatorClass, constructorArguments, whitelist);
						validatorInstances.add(new ParameterizedValidatorWrapper(validatorInstance, constructorArguments, validateMethods, index));
					}
					return validatorInstances;
				}
				catch (ClassCastException e)
				{
					throw new ValidationException("Unable to create " + validatorClass.getCanonicalName() + " test, because parameterized method " + parameterizedMethod.getName() + " has an invalid return type");
				}
				catch (IllegalAccessException | InvocationTargetException e)
				{
					throw new ValidationException("Unable to create " + validatorClass.getCanonicalName() + " test, because parameterized method " + parameterizedMethod.getName() + " could not be invoked ", e);
				}
			}
		}
		catch (IllegalAccessException | InstantiationException | InvocationTargetException e)
		{
			log.error(TargetPhase.class.getSimpleName() + " " + TargetPhase.SETUP + " could not be instantiated.");
			validationResults.addFailedExecution();
			return Collections.emptyList();
		}
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private List<Object[]> generateDataForConstructorArguments(@Nonnull Method parameterizedMethod, @Nonnull Map<String, AuditTableInformation> whitelist) throws IllegalAccessException, InvocationTargetException
	{
		final List<Object> constructorParameters = createParametersForParameterizedMethod(parameterizedMethod, whitelist);
		return (List<Object[]>) parameterizedMethod.invoke(null, constructorParameters.toArray());
	}

	@Nonnull
	private List<Object> createParametersForParameterizedMethod(@Nonnull Method method, @Nonnull Map<String, AuditTableInformation> whitelist)
	{
		final List<Object> methodParameters = new ArrayList<>(method.getParameterCount());
		for (Parameter parameter : method.getParameters())
		{
			final ConnectionProvider connectionProviderAnnotation = parameter.getAnnotation(ConnectionProvider.class);
			if (connectionProviderAnnotation != null)
			{
				methodParameters.add(connectionProvider);
				continue;
			}

			final WhiteList whiteListAnnotation = parameter.getAnnotation(WhiteList.class);
			if (whiteListAnnotation != null)
			{
				methodParameters.add(whitelist);
			}
		}
		return methodParameters;
	}

	@Nonnull
	private Object createValidatorInstance(@Nonnull Class<?> validatorClass, @Nonnull Object[] constructorArguments, @Nonnull Map<String, AuditTableInformation> whitelist) throws InstantiationException, IllegalAccessException, InvocationTargetException
	{
		final Object newInstance = validatorClass.getConstructors()[0].newInstance(constructorArguments);

		final List<Field> declaredFields = ReflectionUtils.getAllDeclaredFields(validatorClass);
		for (Field declaredField : declaredFields)
		{
			declaredField.setAccessible(true);

			final ConnectionProvider connectionProviderAnnotation = declaredField.getAnnotation(ConnectionProvider.class);
			if (connectionProviderAnnotation != null)
			{
				declaredField.set(newInstance, connectionProvider);
				continue;
			}

			final WhiteList whitelistAnnotation = declaredField.getAnnotation(WhiteList.class);
			if (whitelistAnnotation != null)
			{
				declaredField.set(newInstance, whitelist);
			}
		}

		return newInstance;
	}

	@Nonnull
	Map<String, AuditTableInformation> executeValidators(@Nonnull Map<TargetPhase, Set<Class<?>>> validators, @Nonnull TargetPhase targetPhase, @Nonnull Map<String, AuditTableInformation> providedWhitelist, @Nonnull ValidationResults validationResults)
	{
		final Map<String, AuditTableInformation> returnWhitelist = new HashMap<>(providedWhitelist);
		final Set<Class<?>> valdidatorsForTargetPhase = validators.getOrDefault(targetPhase, Collections.emptySet());
		for (Class<?> validator : valdidatorsForTargetPhase)
		{
			if (IgnoreUtils.validationShouldBeIgnored(ignorables, validator))
			{
				validationResults.addClassToIgnored(validator);
				continue;
			}

			final List<ValidatorWrapper> validatorInstances = createValidatorInstances(validator, returnWhitelist, validationResults);

			for (ValidatorWrapper wrapper : validatorInstances)
			{
				final Object validatorInstance = wrapper.getValidator();
				for (Method method : wrapper.getValidateMethods())
				{
					if (IgnoreUtils.validationShouldBeIgnored(ignorables, wrapper, method))
					{
						log.info("Ignored validation method " + wrapper.getValidationName(method));
						validationResults.addMethodToIgnored(method);
						continue;
					}

					log.debug("Started with " + wrapper.getValidationName(method));
					final String validationName = wrapper.getValidationName(method);
					try
					{
						method.invoke(validatorInstance);
						log.debug(validationName + " executed successfully.");
					}
					catch (IllegalAccessException | InvocationTargetException e)
					{
						validationResults.addFailedExecution();

						final String errorMessage;
						if (e.getCause() instanceof ValidationException)
						{
							errorMessage = e.getCause().getMessage();
							if (e.getCause() instanceof SetupValidationForSpecificWhitelistEntryException)
							{
								returnWhitelist.remove(((SetupValidationForSpecificWhitelistEntryException) e.getCause()).getAuditTableInformation().getAuditTableName());
							}
						}
						else
						{
							errorMessage = e.getMessage();
						}
						log.error(validationName + " failed, with the following message: " + errorMessage);
					}
				}
			}
		}
		return returnWhitelist;
	}
}
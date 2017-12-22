package org.tak.zeger.enversvalidationplugin;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.tak.zeger.enversvalidationplugin.annotation.Parameterized;
import org.tak.zeger.enversvalidationplugin.annotation.Validate;
import org.tak.zeger.enversvalidationplugin.annotation.ValidationType;
import org.tak.zeger.enversvalidationplugin.connection.ConnectionProviderInstance;
import org.tak.zeger.enversvalidationplugin.entities.AuditTableInformation;
import org.tak.zeger.enversvalidationplugin.entities.ValidationResults;
import org.tak.zeger.enversvalidationplugin.exceptions.ValidationException;
import org.tak.zeger.enversvalidationplugin.execution.SetupExecutor;
import org.tak.zeger.enversvalidationplugin.utils.PropertyUtils;

@Mojo(name = "validate")
public class EnversValidationMojo extends AbstractMojo
{
	private static final String PACKAGE_TO_ALWAYS_SCAN_FOR_EXECUTORS = "org.tak.zeger.enversvalidationplugin.validate";

	/**
	 * Properties file for the connection information.
	 * Required properties:
	 * - username: db-user used to connect with the database under test.
	 * - password: db-user password used to connect with the database under test.
	 * - driver: database driver class.
	 * - url: jdbc connection string. (E.g. 'jdbc:postgresql://localhost/schemaToTest')
	 */
	@Parameter(property = "connectionPropertyFile", required = true, readonly = true)
	private File connectionPropertyFile;

	/**
	 * Used to define packages which hold user defined validators.
	 * WARNING: Untested feature.
	 *
	 * Validators within these packages will be found based on the {@link ValidationType} annotation.
	 * The actual validator methods should be annotated with the {@link Validate} annotation.
	 */
	@Parameter(property = "packageToScanForValidators", readonly = true)
	private List<String> packageToScanForValidators;

	/**
	 * Used to define validator classes/method that should be ignored.
	 * Validators can be ignored based on their unique identifier, the following cases are supported.
	 *
	 * Class level: E.g. RevisionValidator
	 * Method level: E.g. RevisionValidator.validateAllRecordsInAuditedTableHaveAValidLatestRevision
	 * Individual runs: E.g. RevisionValidator.validateAllRecordsInAuditedTableHaveAValidLatestRevision.AUDIT_TABLE_TO_IGNORE.
	 * (In case of a {@link Validate} method with data generated by a {@link Parameterized} method)
	 */
	@Parameter(property = "ignorables", readonly = true)
	private List<String> ignorables;

	@Override
	public void execute() throws MojoFailureException
	{
		final ConnectionProviderInstance connectionProvider = PropertyUtils.getConnectionProperties(connectionPropertyFile);
		final Map<String, AuditTableInformation> whiteList = PropertyUtils.getWhiteList(connectionProvider.getWhiteListPropertyFile(), connectionProvider.getQueries().getAuditTablePostFix());

		final ValidationResults validationResults = new ValidationResults();
		packageToScanForValidators.add(PACKAGE_TO_ALWAYS_SCAN_FOR_EXECUTORS);
		try
		{
			new SetupExecutor(getLog(), ignorables, connectionProvider).execute(packageToScanForValidators, whiteList, validationResults);
		}
		catch (RuntimeException e)
		{
			getLog().error(e);
			throw new MojoFailureException("Exception occurred: " + e.getMessage());
		}

		final List<Class> validatorClassesIgnored = validationResults.getValidatorClassesIgnored();
		if (!validatorClassesIgnored.isEmpty())
		{
			getLog().info("The following validators were ignored: " + validatorClassesIgnored);
		}

		final int executionsFailed = validationResults.getExecutionsFailed();
		if (executionsFailed > 0)
		{
			throw new ValidationException(executionsFailed + " validations failed, see log above for details.");
		}
	}
}
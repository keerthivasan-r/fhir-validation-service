package uk.nhsd.apim.fhirvalidator;


import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.validation.FhirValidator;

import org.hl7.fhir.common.hapi.validation.support.CachingValidationSupport;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.r4.context.IWorkerContext;
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext;
import org.hl7.fhir.utilities.cache.NpmPackage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import uk.mayfieldis.hapifhir.FHIRServerProperties;
import uk.mayfieldis.hapifhir.validation.IGValidationSupport;
import uk.mayfieldis.hapifhir.PackageManager;
import uk.mayfieldis.hapifhir.validation.RemoteTerminologyServiceValidationSupportOnto;
import uk.mayfieldis.hapifhir.support.ServerFHIRValidation;

import java.util.*;

//CHECKSTYLE:OFF

@SpringBootApplication(exclude = {
         ElasticsearchAutoConfiguration.class, DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@ServletComponentScan()
@ComponentScan({"uk.nhsd.apim.fhirvalidator", "uk.mayfieldis.hapifhir"})
public class ValidationServer extends SpringBootServletInitializer {

    /**
     * A main method to start this application.
     */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ValidationServer.class);

    FhirContext ctx;

    @Autowired
    ApplicationContext context;

    public static void main(String[] args) {

        System.setProperty("hawtio.authenticationEnabled", "false");
        System.setProperty("management.security.enabled","false");
        System.setProperty("management.contextPath","");

        SpringApplication.run(ValidationServer.class, args);
    }

    private ClassLoader getContextClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }




    @Bean
    public FhirContext fhirContext() {
        if (this.ctx == null) {
            this.ctx = FhirContext.forR4();
        }
       return this.ctx;
    }

    @Bean
    public FhirValidator fhirValidator( FhirContext r4ctx) {

        return r4ctx.newValidator();
    }

    @Bean(name="serverIgPackage")
    public NpmPackage getServerIgPackage() throws Exception {
        NpmPackage serverIgPackage =null;

        if (!FHIRServerProperties.getServerIgPackage().isEmpty()) {
            serverIgPackage =
                    PackageManager.getPackage(FHIRServerProperties.getServerIgPackage(),
                    FHIRServerProperties.getServerIgVersion(),
                    FHIRServerProperties.getServerIgUrl());
            if (serverIgPackage== null)  throw new InternalErrorException("Unable to load API Server Conformance package");
        }
        return serverIgPackage;
    }

    @Bean(name="coreIgPackage")
    public NpmPackage getCorenIgPackage() throws Exception {
        NpmPackage validationIgPackage =null;

        if (!FHIRServerProperties.getCoreIgPackage().isEmpty()) {
            validationIgPackage = PackageManager.getPackage(FHIRServerProperties.getCoreIgPackage(),
                    FHIRServerProperties.getCoreIgVersion(),
                    FHIRServerProperties.getCoreIgUrl());
            if (validationIgPackage== null)  throw new InternalErrorException("Unable to load API Server Conformance package");
        }
        return validationIgPackage;
    }

    @Bean
    public ServerFHIRValidation getValidation(FhirValidator val, FhirContext ctx, @Qualifier("serverIgPackage") NpmPackage serverIgPackage) throws Exception {
        return new ServerFHIRValidation(val,ctx,serverIgPackage);
    }

    @Bean
    public FhirInstanceValidator fhirInstanceValidator (FhirValidator val, FhirContext r4ctx, @Qualifier("coreIgPackage") NpmPackage coreIgPackage, @Qualifier("serverIgPackage") NpmPackage serverIgPackage) throws Exception {


        ValidationSupportChain
                validationSupportChain = new ValidationSupportChain();

        DefaultProfileValidationSupport defaultProfileValidationSupport = new DefaultProfileValidationSupport(r4ctx);
        validationSupportChain.addValidationSupport(defaultProfileValidationSupport);


        // IGValidation support acts simlilar to PrePopulatedValidationSupport
        List<NpmPackage> npmPackageList = new ArrayList<>();


        IWorkerContext context = new HapiWorkerContext(r4ctx,validationSupportChain);


        if (coreIgPackage !=null) {

            IGValidationSupport igCoreVS =new IGValidationSupport(r4ctx, coreIgPackage);
            validationSupportChain.addValidationSupport(igCoreVS);
            igCoreVS.createSnapshots(context, validationSupportChain);
        }


        if (serverIgPackage !=null) {
            // Ordering is important here. Need core loaded before validation package
            IGValidationSupport igServerVS = new IGValidationSupport(r4ctx, serverIgPackage);
            validationSupportChain.addValidationSupport(igServerVS);
            igServerVS.createSnapshots(context, validationSupportChain);
        }


        if (FHIRServerProperties.getValidateTerminologyEnabled()) {
           if (FHIRServerProperties.getTerminologyServer() != null) {

               // Use in memory validation first
               // Note: this requires ValueSets to be expanded
               validationSupportChain.addValidationSupport(new InMemoryTerminologyServerValidationSupport(r4ctx));

            // Use ontoserver
            // Create a module that uses a remote terminology service

            RemoteTerminologyServiceValidationSupportOnto remoteTermSvc = new RemoteTerminologyServiceValidationSupportOnto(ctx);
            remoteTermSvc.setBaseUrl(FHIRServerProperties.getTerminologyServer());
            validationSupportChain.addValidationSupport(remoteTermSvc);
           } else {

           }

        }
// Wrap the chain in a cache to improve performance
        CachingValidationSupport cache = new CachingValidationSupport(validationSupportChain);

        // We try the above validators first

        val.setValidateAgainstStandardSchema(FHIRServerProperties.getValidationSchemaFlag());

        val.setValidateAgainstStandardSchematron(FHIRServerProperties.getValidationSchemaFlag());

        FhirInstanceValidator instanceValidator = new FhirInstanceValidator(r4ctx);
        val.registerValidatorModule(instanceValidator);


        instanceValidator.setValidationSupport(validationSupportChain);


        return instanceValidator;
    }


    @Bean
    public ServletRegistrationBean ServletRegistrationBean(FhirContext ctx, @Qualifier("serverIgPackage") NpmPackage serverIgPackage) {

        ServletRegistrationBean registration = new ServletRegistrationBean(new FHIRRestfulServer(context, ctx, serverIgPackage), "/R4/*");
        Map<String,String> params = new HashMap<>();
        params.put("FhirVersion","R4");
        params.put("ImplementationDescription","FHIR Validation Server");
        registration.setInitParameters(params);
        registration.setName("FhirServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }


}
//CHECKSTYLE:ON

package de.viadee.ki.sparkimporter.runner;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import de.viadee.ki.sparkimporter.processing.PreprocessingRunner;
import de.viadee.ki.sparkimporter.processing.steps.PipelineStep;
import de.viadee.ki.sparkimporter.processing.steps.dataprocessing.*;
import de.viadee.ki.sparkimporter.processing.steps.importing.InitialCleanupStep;
import de.viadee.ki.sparkimporter.processing.steps.output.WriteToDiscStep;
import de.viadee.ki.sparkimporter.processing.steps.userconfig.*;
import de.viadee.ki.sparkimporter.util.SparkImporterCSVArguments;
import de.viadee.ki.sparkimporter.util.SparkImporterLogger;
import de.viadee.ki.sparkimporter.util.SparkImporterUtils;
import de.viadee.ki.sparkimporter.util.SparkImporterVariables;
import org.apache.commons.io.FileUtils;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CSVImportAndProcessingRunner extends SparkRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CSVImportAndProcessingRunner.class);
    public static SparkImporterCSVArguments ARGS;

    @Override
    protected void initialize(String[] arguments) {
        ARGS = SparkImporterCSVArguments.getInstance();

        // instantiate JCommander
        // Use JCommander for flexible usage of Parameters
        final JCommander jCommander = JCommander.newBuilder().addObject(SparkImporterCSVArguments.getInstance()).build();
        try {
            jCommander.parse(arguments);
        } catch (final ParameterException e) {
            LOG.error("Parsing of parameters failed. Error message: " + e.getMessage());
            jCommander.usage();
            System.exit(1);
        }

        //workaround to overcome the issue that different Application argument classes are used but we need the target folder for the result steps
        SparkImporterVariables.setTargetFolder(ARGS.getFileDestination());
        SparkImporterVariables.setDevTypeCastCheckEnabled(ARGS.isDevTypeCastCheckEnabled());
        SparkImporterVariables.setRevCountEnabled(ARGS.isRevisionCount());
        SparkImporterVariables.setSaveMode(ARGS.getSaveMode() == SparkImporterVariables.SAVE_MODE_APPEND ? SaveMode.Append : SaveMode.Overwrite);
        SparkImporterVariables.setOutputFormat(ARGS.getOutputFormat());
        SparkImporterUtils.setWorkingDirectory(ARGS.getWorkingDirectory());
        SparkImporterLogger.setLogDirectory(ARGS.getLogDirectory());

        dataLevel = SparkImporterVariables.DATA_LEVEL_PROCESS;

        PreprocessingRunner.writeStepResultsIntoFile = ARGS.isWriteStepResultsToCSV();

        // Delete destination files, required to avoid exception during runtime
        if(SparkImporterVariables.getSaveMode().equals(SaveMode.Overwrite)) {
            FileUtils.deleteQuietly(new File(ARGS.getFileDestination()));
        }

        SparkImporterLogger.getInstance().writeInfo("Starting CSV import and processing");
        SparkImporterLogger.getInstance().writeInfo("Importing CSV file: " + ARGS.getFileSource());
    }

    @Override
    protected List<PipelineStep> buildDefaultPipeline() {
        List<PipelineStep> pipelineSteps = new ArrayList<>();

        pipelineSteps.add(new PipelineStep(new DataFilterStep(), ""));
        pipelineSteps.add(new PipelineStep(new ColumnRemoveStep(), "DataFilterStep"));
        pipelineSteps.add(new PipelineStep(new ReduceColumnsDatasetStep(), "ColumnRemoveStep"));
        pipelineSteps.add(new PipelineStep(new VariableFilterStep(), "ReduceColumnsDatasetStep"));
        pipelineSteps.add(new PipelineStep(new VariableNameMappingStep(), "VariableFilterStep"));
        pipelineSteps.add(new PipelineStep(new DetermineVariableTypesStep(), "VariableNameMappingStep"));
        pipelineSteps.add(new PipelineStep(new VariablesTypeEscalationStep(), "DetermineVariableTypesStep"));
        pipelineSteps.add(new PipelineStep(new AggregateVariableUpdatesStep(), "VariablesTypeEscalationStep"));
        pipelineSteps.add(new PipelineStep(new AddVariablesColumnsStep(), "AggregateVariableUpdatesStep"));
        pipelineSteps.add(new PipelineStep(new AggregateProcessInstancesStep(), "AddVariablesColumnsStep"));
        pipelineSteps.add(new PipelineStep(new CreateColumnsFromJsonStep(), "AggregateProcessInstancesStep"));
        pipelineSteps.add(new PipelineStep(new JsonVariableFilterStep(), "CreateColumnsFromJsonStep"));
        pipelineSteps.add(new PipelineStep(new AddReducedColumnsToDatasetStep(), "JsonVariableFilterStep"));
        pipelineSteps.add(new PipelineStep(new ColumnHashStep(), "AddReducedColumnsToDatasetStep"));
        pipelineSteps.add(new PipelineStep(new TypeCastStep(), "ColumnHashStep"));
        pipelineSteps.add(new PipelineStep(new WriteToDiscStep(), "TypeCastStep"));

        return pipelineSteps;
    }

    @Override
    protected Dataset<Row> loadInitialDataset() {

        //Load source CSV file
        Dataset<Row> dataset = sparkSession.read()
                .option("inferSchema", "true")
                .option("delimiter", ARGS.getDelimiter())
                .option("header", "true")
                .option("ignoreLeadingWhiteSpace", "false")
                .option("ignoreTrailingWhiteSpace", "false")
                .csv(ARGS.getFileSource());

        // write imported CSV structure to file for debugging
        if (SparkImporterCSVArguments.getInstance().isWriteStepResultsToCSV()) {
            SparkImporterUtils.getInstance().writeDatasetToCSV(dataset, "import_result");
        }

        InitialCleanupStep initialCleanupStep = new InitialCleanupStep();
        dataset = initialCleanupStep.runPreprocessingStep(dataset, false, SparkImporterVariables.DATA_LEVEL_PROCESS, null);

        return dataset;
    }
}

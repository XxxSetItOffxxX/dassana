package app.dassana.core.contentmanager;

import app.dassana.core.api.DassanaWorkflowValidationException;
import app.dassana.core.contentmanager.model.SyncResult;
import app.dassana.core.contentmanager.model.WorkflowProcessingResult;
import app.dassana.core.launch.model.Message;
import app.dassana.core.launch.model.Request;
import app.dassana.core.launch.model.WorkflowNotFoundException;
import app.dassana.core.normalize.model.NormalizerWorkflow;
import app.dassana.core.policycontext.model.PolicyContext;
import app.dassana.core.resource.model.GeneralContext;
import app.dassana.core.resource.model.ResourceContext;
import app.dassana.core.risk.model.RiskConfig;
import app.dassana.core.risk.model.Rule;
import app.dassana.core.rule.MatchType;
import app.dassana.core.util.StringyThings;
import app.dassana.core.workflow.model.*;
import io.micronaut.core.util.StringUtils;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.inject.Singleton;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class ContentManager implements ContentManagerApi {

  private static final int SYNC_INTERVAL_IN_SECONDS = 30;

  private final WorkflowApi contentManager;
  private final Set<Workflow> workflowSet = ConcurrentHashMap.newKeySet();
  Map<String, String> workflowIdToYamlContext = new HashMap<>();
  Map<String, String> workflowIdToDefaultContext = new HashMap<>();
  Set<String> modifiedWorkflowIds = new HashSet<>();
  private long lastUpdated = 0;

  public static final String VENDOR_ID = "vendor-id";
  public static final String POLICY_CONTEXT = "policy-context";
  public static final String POLICY_CONTEXT_CLASS = "class";
  public static final String POLICY_CONTEXT_SUBCLASS = "subclass";
  public static final String POLICY_CONTEXT_CAT = "category";
  public static final String POLICY_CONTEXT_SUB_CAT = "subcategory";
  public static final String POLICY_CONTEXT_FILTERS = "filters";
  public static final String GENERAL_CONTEXT = "general-context";
  public static final String RESOURCE_CONTEXT = "resource-context";
  public static final String RESOURCE_CONTEXT_CSP = "csp";
  public static final String RESOURCE_CONTEXT_SERVICE = "service";
  public static final String RESOURCE_CONTEXT_TYPE = "resource-type";

  public static final String NORMALIZE = "normalize";
  public static final String WORKFLOW_ID = "workflowId";

  public enum FIELDS {
    RISK("risk"),
    CLASS("class"),
    SUB_CLASS("subclass"),
    CATEGORY("category"),
    SUB_CATEGORY("subcategory"),
    STEPS("steps"),
    USES("uses"),
    CSP("csp"),
    SERVICE("service"),
    TYPE("type"),
    OUTPUT("output"),
    VENDOR_ID("vendor-id"),
    RESOURCE_TYPE("resource-type");

    private final String name;

    FIELDS(final String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  public Map<String, String> getWorkflowIdToDefaultContext() {
    return workflowIdToDefaultContext;
  }

  public boolean isModifiedWorkflow(String workflowId){
    return modifiedWorkflowIds.contains(workflowId);
  }

  private static final Logger logger = LoggerFactory.getLogger(ContentManager.class);


  public ContentManager(WorkflowApi contentDownloader) {
    this.contentManager = contentDownloader;
    processEmbeddedContent();
    loadDefaultWorkflows();
    syncContent(0L, null);//because we are in init/constructor, we fetch all workflows from s3
  }

  void processEmbeddedContent() {
    String embeddedContentPath;
    try {
      embeddedContentPath = Thread.currentThread().getContextClassLoader().getResource("content/workflows/").getFile();
    } catch (NullPointerException e) {
      throw new IllegalArgumentException("Unable to read embedded content, make sure to run mvn clean "
          + "process-resources first if you are running it locally ");
    }
    processDir(new File(embeddedContentPath));

  }

  private void loadDefaultWorkflows(){
    for(String id : workflowIdToYamlContext.keySet()){
      workflowIdToDefaultContext.put(id, String.valueOf(workflowIdToYamlContext.get(id)));
    }
  }

  public Map<String, String> getWorkflowIdToYamlContext() {
    return workflowIdToYamlContext;
  }

  public String deleteWorkflow(String workflowId){

    if(workflowIdToYamlContext.containsKey(workflowId) && modifiedWorkflowIds.contains(workflowId)){
      try {
        String jsonFromYaml = StringyThings.getJsonFromYaml(workflowIdToYamlContext.get(workflowId));
        Workflow workflow = getWorkflow(new JSONObject(jsonFromYaml));
        workflowIdToYamlContext.remove(workflowId);
        workflowSet.remove(workflow);
        modifiedWorkflowIds.remove(workflowId);
      }catch (Exception e){
        throw new RuntimeException("Failed to parse workflow");
      }
    }

    contentManager.deleteContent(workflowId);
    return String.format("Deleting workflow with id: %s", workflowId);
  }

  RiskConfig getRiskConfig(JSONObject workFlowJson) {

    RiskConfig riskConfig = new RiskConfig();
    List<Rule> riskRules = new LinkedList<>();

    JSONObject riskConfigObj = workFlowJson.optJSONObject("risk-config");

    if (riskConfigObj != null) {
      riskConfig.setDefaultRisk(riskConfigObj.getString("default-risk"));
      JSONArray rulesJsonArray = riskConfigObj.optJSONArray("rules");

      if (rulesJsonArray != null) {
        for (int i = 0; i < rulesJsonArray.length(); i++) {
          JSONObject ruleObj = rulesJsonArray.getJSONObject(i);
          String name = ruleObj.getString("name");
          String condition = ruleObj.getString("condition");
          String risk = ruleObj.getString("risk");

          Rule rule = new Rule(name, condition, risk);
          riskRules.add(rule);

        }
        riskConfig.setRiskRules(riskRules);
      }


    }
    return riskConfig;

  }


  PolicyContext getPolicyContext(JSONObject jsonObject) {

    PolicyContext policyContext = new PolicyContext();
    policyContext.setCategory(jsonObject.optString("category"));
    policyContext.setSubCategory(jsonObject.optString("subcategory"));

    return policyContext;


  }

  //TODO add alertClass and subclass to PolicyContext
  public Workflow getWorkflow(JSONObject jsonObject) {
    Workflow workflow = new Workflow();

    String type = jsonObject.optString("type");

    if (StringUtils.isNotEmpty(type) && type.contentEquals(NORMALIZE)) {
      workflow = new NormalizerWorkflow();

      ((NormalizerWorkflow) workflow).setVendorId(jsonObject.getString("vendor-id"));

      JSONObject postProcessor = jsonObject.optJSONObject("post-processor");

      List<Step> postProcessorSteps = new LinkedList<>();

      if (postProcessor != null) {
        JSONArray postProcessorStepsJsonArray = postProcessor.optJSONArray("steps");

        if (postProcessorStepsJsonArray != null) {
          for (int i = 0; i < postProcessorStepsJsonArray.length(); i++) {
            JSONObject stepsJSONObject = postProcessorStepsJsonArray.getJSONObject(i);
            Step step = new Step();
            step.setId(stepsJSONObject.getString("id"));
            step.setUses(stepsJSONObject.getString("uses"));
            postProcessorSteps.add(step);
          }
        }
      }
      ((NormalizerWorkflow) workflow).setPostProcessorSteps(postProcessorSteps);
      var outputQueue = jsonObject.optJSONObject("output-queue");
      if (outputQueue != null) {
        boolean outputQueueEnabled = outputQueue.getBoolean("enabled");
        ((NormalizerWorkflow) workflow).setOutputQueueEnabled(outputQueueEnabled);
      } else {
        ((NormalizerWorkflow) workflow).setOutputQueueEnabled(false);
      }
    } else if (type.contentEquals(POLICY_CONTEXT)) {
      workflow = getPolicyContext(jsonObject);
      ((PolicyContext) workflow).setCategory(jsonObject.optString(POLICY_CONTEXT_CAT));
      ((PolicyContext) workflow).setSubCategory(jsonObject.optString(POLICY_CONTEXT_SUB_CAT));
      ((PolicyContext) workflow).setRiskConfig(getRiskConfig(jsonObject));

      PolicyContext policyContext = (PolicyContext) workflow;
      policyContext.setVendorFilters(getFilters(jsonObject));


    } else if (type.contentEquals(GENERAL_CONTEXT)) {
      workflow = new GeneralContext();
      ((GeneralContext) workflow).setCsp(jsonObject.getString("csp"));
      ((GeneralContext) workflow).setRiskConfig(getRiskConfig(jsonObject));
    } else if (type.contentEquals(RESOURCE_CONTEXT)) {
      workflow = new ResourceContext();
      ((ResourceContext) workflow).setService(jsonObject.getString("service"));
      ((ResourceContext) workflow).setResourceType(jsonObject.getString("resource-type"));
      ((ResourceContext) workflow).setRiskConfig(getRiskConfig(jsonObject));
    } else {
      DassanaWorkflowValidationException dassanaWorkflowValidationException = new DassanaWorkflowValidationException();
      LinkedList<Message> messages = new LinkedList<>();
      messages.add(new Message("Sorry, we don't recognize workflow type ".concat(type)));
      dassanaWorkflowValidationException.setMessages(messages);
      throw dassanaWorkflowValidationException;
    }

    workflow.setSchema(jsonObject.getInt("schema"));
    workflow.setType(jsonObject.getString("type"));
    workflow.setId(jsonObject.getString("id"));
    workflow.setName(jsonObject.optString("name"));
    JSONArray labels = jsonObject.optJSONArray("labels");
    if (labels != null) {
      var labelsArray = new LinkedList<String>();
      for (int i = 0; i < labels.length(); i++) {
        labelsArray.add(labels.getString(i));
      }
      workflow.setLabels(labelsArray);
    }

    workflow.setOutput(getOutputs(jsonObject));
    workflow.setSteps(getSteps(jsonObject));

    if (!workflow.getType().equals(POLICY_CONTEXT)) {

      List<VendorFilter> filters = getFilters(jsonObject);
      List<Filter> filterList = new LinkedList<>();
      for (VendorFilter filter : filters) {
        Filter simpleFilter = new Filter();
        simpleFilter.setRules(filter.getRules());
        simpleFilter.setMatchType(filter.getMatchType());
        filterList.add(simpleFilter);
      }

      workflow.setFilters(filterList);

    }

    return workflow;


  }

  private List<Output> getOutputs(JSONObject jsonObject) {

    List<Output> outputs = new LinkedList<>();
    JSONArray optOutputJsonArray = jsonObject.optJSONArray("output");
    if (optOutputJsonArray != null) {
      for (int i = 0; i < optOutputJsonArray.length(); i++) {
        JSONObject outputObj = optOutputJsonArray.getJSONObject(i);
        Output output = new Output();
        output.setValue(outputObj.getString("value"));
        output.setName(outputObj.getString("name"));
        String valueType = outputObj.optString("value-type");
        if (StringUtils.isEmpty(valueType)) {
          output.setValueType(ValueType.JQ_EXPRESSION);
        } else {
          output.setValueType(ValueType.valueOf(valueType));
        }
        outputs.add(output);
      }

    }
    return outputs;

  }


  private List<VendorFilter> getFilters(JSONObject jsonObject) {
    List<VendorFilter> filters = new LinkedList<>();

    JSONArray optJSONArray = jsonObject.optJSONArray("filters");
    if (optJSONArray != null) {
      for (int i = 0; i < optJSONArray.length(); i++) {
        VendorFilter filter = new VendorFilter();
        JSONObject filterObj = optJSONArray.getJSONObject(i);
        String matchType = filterObj.getString("match-type");
        if (matchType.toLowerCase().contentEquals("any")) {
          filter.setMatchType(MatchType.ANY);
        } else if (matchType.toLowerCase().contentEquals("all")) {
          filter.setMatchType(MatchType.ALL);
        } else {
          throw new IllegalArgumentException("Unrecognized filter type ".concat(matchType));
        }
        JSONArray rulesJsonArray = filterObj.optJSONArray("rules");
        List<String> rules = new LinkedList<>();
        if (rulesJsonArray != null) {
          for (int j = 0; j < rulesJsonArray.length(); j++) {
            String rule = rulesJsonArray.optString(j);
            rules.add(rule);
          }
        }

        filter.setRules(rules);
        filters.add(filter);
      }

    }
    return filters;

  }


  private synchronized List<String> updateWorkflowSet(List<String> workflowYamlStr) throws IOException {

    if (workflowYamlStr == null || workflowYamlStr.isEmpty()) {
      return new LinkedList<>();
    }
    List<String> workflowIdsProcessed = new LinkedList<>();

    for (String s : workflowYamlStr) {
      String jsonFromYaml = StringyThings.getJsonFromYaml(s);
      Workflow workflow = getWorkflow(new JSONObject(jsonFromYaml));
      workflowSet.remove(workflow);
      workflowSet.add(workflow);
      workflowIdsProcessed.add(workflow.getId());
    }

    return workflowIdsProcessed;

  }

  private List<Step> getSteps(JSONObject jsonObject) {

    JSONArray steps = jsonObject.optJSONArray("steps");

    List<Step> stepSet = new LinkedList<>();

    if (steps != null) {
      for (int i = 0; i < steps.length(); i++) {
        JSONObject stepsJSONObject = steps.getJSONObject(i);
        Step step = new Step();
        step.setId(stepsJSONObject.getString("id"));
        step.setUses(stepsJSONObject.getString("uses"));

        JSONArray withKey = stepsJSONObject.optJSONArray("with");

        if (withKey != null) {
          List<Map<String, String>> fields = new LinkedList<>();

          for (int j = 0; j < withKey.length(); j++) {

            JSONObject kvp = withKey.getJSONObject(j);

            Map<String, String> field = new HashMap<>();
            field.put("name", kvp.getString("name"));
            field.put("value", kvp.getString("value"));
            field.put("value-type", kvp.optString("value-type"));
            fields.add(field);
          }
          step.setFields(fields);

        } else {
          step.setFields(new LinkedList<>());
        }

        stepSet.add(step);

      }
    }
    return stepSet;

  }

  public WorkflowProcessingResult processDir(File dir) {
    return processDir(dir, false);
  }

  public WorkflowProcessingResult processDir(File dir, boolean isModified) {
    WorkflowProcessingResult workflowProcessingResult = new WorkflowProcessingResult();
    Map<String, Exception> fileToExceptionMap = new HashMap<>();

    File[] files = dir.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) {
          processDir(file);
        } else {
          try {
            String readFileToString = FileUtils.readFileToString(file, Charset.defaultCharset());
            List<String> workflowYamlList = new LinkedList<>();
            workflowYamlList.add(readFileToString);
            List<String> workflowIdsProcessed = updateWorkflowSet(workflowYamlList);
            String workflowId = workflowIdsProcessed.get(0);
            workflowIdToYamlContext.put(workflowId, readFileToString);
            if(isModified){
              modifiedWorkflowIds.add(workflowId);
            }
          } catch (Exception e) {
            fileToExceptionMap.put(file.getName(), e);
            logger.warn("{} will be skipped due to error ", file, e);
          }
        }
      }
    }

    workflowProcessingResult.setWorkflowFileToExceptionMap(fileToExceptionMap);
    return workflowProcessingResult;
  }

  private void resetWorkflows(){
    if(modifiedWorkflowIds.isEmpty()) return;

    try {
      List<String> workflowYaml = new ArrayList<>();
      for (String id : modifiedWorkflowIds) {
        if(workflowIdToDefaultContext.containsKey(id)) {
          String yaml = workflowIdToDefaultContext.get(id);
          workflowIdToYamlContext.put(id, yaml);
          workflowYaml.add(yaml);
        }
      }
      updateWorkflowSet(workflowYaml);
    }catch (Exception e){
      throw new RuntimeException("Failed to reset workflows");
    }
  }



  public SyncResult syncContent(Long lastSuccessfulSync, Request request) {

    SyncResult syncResult = new SyncResult();
    syncResult.setCacheHit(true);

    boolean stale = System.currentTimeMillis() - lastUpdated > TimeUnit.SECONDS.toMillis(SYNC_INTERVAL_IN_SECONDS);

    //sync every SYNC_INTERVAL_IN_MINS
    if ((request != null && request.isRefreshFromS3()) || stale) {

      syncResult.setCacheHit(false);
      Optional<File> optionalFile = contentManager.downloadContent();

      if(optionalFile.isPresent()){
        WorkflowProcessingResult workflowProcessingResult = processDir(optionalFile.get(), true);
        syncResult.setSuccessful(workflowProcessingResult.getWorkflowFileToExceptionMap().size() <= 0);
      }else{
        resetWorkflows();
      }

      lastUpdated = System.currentTimeMillis();
    }
    syncResult.setContentLastSyncTimeMs(lastUpdated);
    return syncResult;
  }

  @Override
  public Set<Workflow> getWorkflowSet(Request request) throws Exception {
    syncContent(lastUpdated, request);

    //if the additional workflows are provided,we use them. This is for the editor.dassana.io use case where we are
    // editing workflows - this occurs when workflow is in draft mode, i.e. modified but not saved
    if (request.getAdditionalWorkflowYamls() != null
        && request.getAdditionalWorkflowYamls().size() > 0) {
      List<String> additionalWorkflowYamls = request.getAdditionalWorkflowYamls();

      //we want to run only the workflow provided, so we clone the workflowSet and add it
      Set<Workflow> workflowSetToUse = new HashSet<>(workflowSet);
      for (String workflowYamlStr : additionalWorkflowYamls) {
        String jsonFromYaml = StringyThings.getJsonFromYaml(workflowYamlStr);
        Workflow workflow = getWorkflow(new JSONObject(jsonFromYaml));
        workflowSetToUse.remove(workflow);
        workflowSetToUse.add(workflow);
      }
      return workflowSetToUse;
    }

    return workflowSet;
  }

  @Override
  public Map<String, Workflow> getWorkflowIdToWorkflowMap(Request request) throws Exception {

    Map<String, Workflow> map = new HashMap<>();
    getWorkflowSet(request).forEach(workflow -> map.put(workflow.getId(), workflow));
    return map;
  }

}

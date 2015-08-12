package com.abilash.nupictest;

import io.cortical.rest.model.Fingerprint;
import io.cortical.rest.model.Term;
import io.cortical.services.Expressions;
import io.cortical.services.RetinaApis;
import io.cortical.services.Terms;
import io.cortical.services.api.client.ApiException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.numenta.nupic.Parameters;
import org.numenta.nupic.Parameters.KEY;
import org.numenta.nupic.network.Network;
import org.numenta.nupic.research.TemporalMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class NupicSuggestions {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(NupicSuggestions.class);
	
	public Network network;
	private List<String[]> input;
	
	private static final String RETINA_NAME = "en_associative";    
    private static final String RETINA_IP = "api.cortical.io";
    
    private static String apiKey = "aeced730-3c66-11e5-9e69-03c0722e0d16";
    
    private Terms termsApi;
    private Expressions exprApi;
    
    static String cachePath = System.getProperty("user.home").concat(File.separator).
            concat(".cortical").concat(File.separator).concat("cache");
    private Map<String, Term> cache;
	
	private Parameters createParameters(){
        org.numenta.nupic.Parameters tmParams = org.numenta.nupic.Parameters.getTemporalDefaultParameters();
        tmParams.setParameterByKey(KEY.COLUMN_DIMENSIONS, new int[] { 16384 });
        tmParams.setParameterByKey(KEY.CELLS_PER_COLUMN, 8);
        tmParams.setParameterByKey(KEY.CONNECTED_PERMANENCE, 0.5);
        tmParams.setParameterByKey(KEY.INITIAL_PERMANENCE, 0.4);
        tmParams.setParameterByKey(KEY.MIN_THRESHOLD, 164);
        tmParams.setParameterByKey(KEY.MAX_NEW_SYNAPSE_COUNT, 164);
        tmParams.setParameterByKey(KEY.PERMANENCE_INCREMENT, 0.1);
        tmParams.setParameterByKey(KEY.PERMANENCE_DECREMENT, 0.0);
        tmParams.setParameterByKey(KEY.ACTIVATION_THRESHOLD, 164);
        return tmParams;
    
	}
	
	private Network createNetwork(){
        org.numenta.nupic.Parameters temporalParams = createParameters();
        network = Network.create("WalmartLabs Suggestions", temporalParams)
            .add(Network.createRegion("Region 1")
                .add(Network.createLayer("Layer 2/3", temporalParams)
                    .add(new TemporalMemory())));
        return network;            
    }
	
	public static void main(String[] args) throws JsonProcessingException {
		NupicSuggestions nupicSuggestions = new NupicSuggestions();
		nupicSuggestions.setInputData(nupicSuggestions.readInputData("foxeat.csv"));
		nupicSuggestions.loadCache();
		
		nupicSuggestions.connectionValid(apiKey);
		
		Network createdNetwork = nupicSuggestions.createNetwork();
		LOGGER.info("Started Feeding the network");
		nupicSuggestions.feedNetwork(createdNetwork, nupicSuggestions.inputIterator());
		LOGGER.info("Completed Feeding the network");
		Term foxTerm = nupicSuggestions.feedQuestion(createdNetwork, new String[]{"fox","eat","something"});
		if(foxTerm!=null){
			System.out.println("Found the answer of Fox eat to be : "+foxTerm.toJson().toString());
		}
		Term elephantTerm = nupicSuggestions.feedQuestion(createdNetwork, new String[]{"elephant","eat","what"});
		if(elephantTerm!=null){
			System.out.println("Found the answer of Elephant eat to be : "+elephantTerm.toJson().toString());
		}
		Term catLikesTerm = nupicSuggestions.feedQuestion(createdNetwork, new String[]{"cat","likes","what"});
		if(catLikesTerm!=null){
			System.out.println("Found the answer of Cat Likes to be : "+catLikesTerm.toJson().toString());
		}
	}
	
	/**
     * Returns an {@link Iterator} over the list of string arrays (lines)
     * @return
     */
    Iterator<String[]> inputIterator() {
        return input.iterator();
    }
    
    /**
     * Returns a {@link List} of String arrays, each representing
     * a line of text.
     * 
     * @param pathToFile
     * @return
     */
    List<String[]> readInputData(String pathToFile) {
        Stream<String> stream = getInputDataStream(pathToFile);
        
        List<String[]> list = stream.map(l -> { return (String[])l.split("[\\s]*\\,[\\s]*"); }).collect(Collectors.toList());
        
        return list;
    }
    
    /**
     * Returns a Stream from the specified file path
     * 
     * @param pathToFile
     * @return
     */
    Stream<String> getInputDataStream(String pathToFile) {
        Stream<String> retVal = null;
        
        Path path = Paths.get("/Users/aamarth/Desktop/foxeat.csv");
        try {
            if(!path.toFile().exists()) {
                throw new FileNotFoundException(pathToFile);
            }
            
            retVal = Files.lines(path, Charset.forName("UTF-8"));
        }catch(Exception e) {
            e.printStackTrace();
        }
        
        return retVal;
    }
    
    /**
     * Called by {@link #checkCache(boolean, boolean)} for every line in
     * the cache file to validate the contents of the specified {@link Term}
     * 
     * @param key
     * @param t
     * @param print
     * @return
     */
    boolean checkTerm(String key, Term t, boolean print) {
        Fingerprint fp = t.getFingerprint();
        if(fp == null) {
            if(print) { LOGGER.debug("\tkey: " + key + ", missing fingerprint"); }
            return false;
        }
        
        int[] pos = fp.getPositions();
        if(pos == null) {
            if(print) { LOGGER.debug("\tkey: " + key + ", has null positions"); }
            return false;
        }
        
        if(pos.length < 1) {
            if(print) { LOGGER.debug("\tkey: " + key + ", had empty positions"); }
            return false;
        }
        
        int sdrLen = pos.length;
        if(print) {
            LOGGER.debug("\tkey: " + key + ", term len: " + sdrLen);
        }
        
        return true;
    }
    
    void feedNetwork(Network network, Iterator<String[]> it) {
    	for(;it.hasNext();) {
    		String[] next = it.next();

    		if(!it.hasNext()) {
    			break;
    		}

    		feedLine(network, next);
    	}
    }
    
    void feedLine(Network network, String[] phrase) {
        for(String term : phrase) {
            int[] sdr = getFingerprintSDR(term);
            network.compute(sdr);
        }
        network.reset();
    }
    
    /**
     * Feeds the {@link Network with the final phrase consisting of the first
     * two words of the final question "fox, eats, ...".
     * 
     * @param network   the current {@link Network} object
     * @param it        an {@link Iterator} over the input source file lines
     * @return
     */
    Term feedQuestion(Network network, String[] phrase) {
        for(int i = 0;i < 2;i++) {
            int[] sdr = getFingerprintSDR(phrase[i]);
            network.compute(sdr);
        }
        
        int[] prediction = network.lookup("Region 1").lookup("Layer 2/3").getPredictedColumns();
        Term term = getClosestTerm(prediction);
        cache.put(term.getTerm(), term);
        network.reset();
        return term;
    }
    
    /**
     * Returns the most similar {@link Term} for the term represented by
     * the specified sdr
     * 
     * @param sdr   sparse int array
     * @return
     */
    Term getClosestTerm(int[] sdr) {
        Fingerprint fp = new Fingerprint(sdr);
        try {
            List<Term> terms = getSimilarTerms(fp);
            
            // Retrieve terms from cache if present
            for(int i = 0;i < terms.size();i++) {
                if(cache.containsKey(terms.get(i).getTerm())) {
                    terms.set(i, cache.get(terms.get(i).getTerm()));
                }
            }
            
            Term retVal = null;
            if(terms != null && terms.size() > 0) {
                retVal = terms.get(0);
                if(checkTerm(retVal.getTerm(), retVal, true)) {
                    return retVal;
                }
                
                // Cache fall through incomplete term for next time
                cache.put(retVal.getTerm(), retVal = getTerms(retVal.getTerm(), true).get(0));
                return retVal;
            }
        }catch(Exception e) {
            LOGGER.debug("Problem using Expressions API");
            e.printStackTrace();
        }
        
        return null;
    }
    
    /**
     * Returns a list of similar terms using the Expressions API.
     * 
     * @param fp    a Fingerprint from which to get similar terms.
     * @return  List view of similar {@link Term}s
     * @throws ApiException
     * @throws JsonProcessingException
     */
    List<Term> getSimilarTerms(Fingerprint fp) throws ApiException, JsonProcessingException {
        return exprApi.getSimilarTerms(fp);
    }
    
    /**
     * Returns the SDR which is the sparse integer array
     * representing the specified term.
     * 
     * @param term
     * @return
     */
    int[] getFingerprintSDR(String term) {
        return getFingerprintSDR(getFingerprint(term));
    }
    
    /**
     * Returns the SDR which is the sparse integer array
     * representing the specified term.
     * 
     * @param fp
     * @return
     */
    int[] getFingerprintSDR(Fingerprint fp) {
        return fp.getPositions();
    }
    
    /**
     * Returns a {@link Fingerprint} for the specified term.
     * 
     * @param term
     * @return
     */
    Fingerprint getFingerprint(String term) {
        try {
            Term t = cache.get(term) == null ?
                getTerms(term, true).get(0) :
                    cache.get(term);
                
            if(!checkTerm(t.getTerm(), t, true)) {
                throw new IllegalStateException("Checkterm failed: " + t.getTerm());
            }
                
            cache.put(t.getTerm(), t);
            
            return t.getFingerprint();
        }catch(Exception e) {
            LOGGER.debug("Problem retrieving fingerprint for term: " + term);
        }
        
        return null;
    }
    
    /**
     * Returns a list of {@link Term}s using the Terms API.
     * 
     * @param term  a term.
     * @param includeFingerprint    true if call should return the positions array,
     *                              false if not.
     * @return
     * @throws ApiException
     * @throws JsonProcessingException
     */
    List<Term>  getTerms(String term, boolean includeFingerprint) throws ApiException, JsonProcessingException {
        return termsApi.getTerm(term, includeFingerprint);
    }
    
    /**
     * Used for testing to set the cache file path
     * @param path
     */
    void setCachePath(String path) {
        cachePath = path;
    }
    
    /**
     * Returns the cache {@link File} specified by the pre-configured file path.
     * (see {@link #Demo(String)}) The cache file is by default stored in the user's
     * home directory in the ".cortical/cache" file. This file stores the {@link Term}
     * objects retrieved via the Cortical.io API so that the server is "pounded" by 
     * queries from this demo.
     * 
     * @return
     */
    File getCacheFile() {
        File f = new File(cachePath);
        if(!f.exists()) {
            try {
                new File(cachePath.substring(0, cachePath.lastIndexOf(File.separator))).mkdir();
                f.createNewFile();
            } catch(IOException e) {
                e.printStackTrace();
                throw new IllegalStateException("Unable to write cache file.");
            }
            
            LOGGER.debug("Created cache file: " + cachePath);
        }
        return f;
    }
    
    /**
     * Returns the {@link Fingerprint} cache.
     * @return
     */
    Map<String, Term> getCache() {
        return cache;
    }
    
    /**
     * Returns the persisted cache as a {@link Stream}
     * @return
     */
    Stream<String> getCacheStream() throws IOException {
        File f = getCacheFile();
        Stream<String> stream = Files.lines(f.toPath());
        
        return stream;
    }
    
    /**
     * Loads the fingerprint cache file into memory if it exists. If it
     * does not exist, this method creates the file; however it won't be
     * written to until the demo is finished processing, at which point
     * {@link #writeCache()} is called to store the cache file.
     */
    void loadCache() {
        if(cache == null) {
            cache = new HashMap<>();
        }
        
        String json = null;
        try {
            StringBuilder sb = new StringBuilder();
            getCacheStream().forEach(l -> { sb.append(l); });
            json = sb.toString();
        } catch(IOException e) {
            e.printStackTrace();
        }
        
        if(json.isEmpty()) {
            LOGGER.debug("Term cache is empty.");
            return;
        }
        
        ObjectMapper mapper = new ObjectMapper();
        List<Term> terms = null;
        try {
            terms = Arrays.asList(mapper.readValue(json, Term[].class));
            if(terms == null) {
                LOGGER.debug("Term cache is empty or malformed.");
            }
        } catch(IOException e) {
            e.printStackTrace();
        }
        
        for(Term t : terms) {
            cache.put(t.getTerm(), t);
        }
        
        checkCache(true, true);
    }
    
    /**
     * Writes the fingerprint cache file to disk. This takes place at the
     * end of the demo's processing.
     */
    void writeCache() {
        File f = getCacheFile();
        
        try(PrintWriter pw = new PrintWriter(new FileWriter(f))) {
            StringBuilder builderStr = new StringBuilder();
            int i = 0;
            for(Iterator<Term> it = cache.values().iterator();it.hasNext();i++) {
                Term t = it.next();
                String termStr = Term.toJson(t);
                if(i > 0) {
                    termStr = termStr.substring(1).trim();
                }
                termStr = termStr.substring(0, termStr.length() - 1).trim();
                builderStr.append(termStr).append(",");
            }
            builderStr.setLength(builderStr.length() - 1);
            builderStr.append(" ]");
            
            pw.println(builderStr.toString());
            pw.flush();
        } catch(IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Called to check the coherence of the cache file to make sure there
     * is no missing data and that the fingerprint {@link Term}s stored internally are
     * valid.
     * 
     * @param print         flag indicating whether the check should be logged
     * @param failOnCheck   flag indicating whether a failed cache entry should cause this
     *                      demo to exit. 
     */
    void checkCache(boolean print, boolean failOnCheck) {
        int count = 0;
        for(String key : cache.keySet()) {
            if(print) { LOGGER.debug((count++) + ". key: " + key); }
            
            if(!checkTerm(key, cache.get(key), print)) {
                if(failOnCheck) {
                    throw new IllegalStateException("Term cache for key: " + key + " was invalid or missing data.");
                }
            }
        }
    }
    
    /**
     * Initializes the Retina API end point, 
     * and returns a flag indicating whether the apiKey
     * is valid and a connection has been configured.
     * 
     * @param apiKey
     */
    @SuppressWarnings("static-access")
	boolean connectionValid(String apiKey) {
        try {
            this.apiKey = apiKey;
            RetinaApis ra = new RetinaApis(RETINA_NAME, RETINA_IP, this.apiKey);
            termsApi = ra.termsApi();
            exprApi = ra.expressionsApi();
            
            LOGGER.debug("Successfully initialized retinal api");
            
            return true;
        }catch(Exception e) {
            LOGGER.debug("Problem initializing retinal api");
            return false;
        }
    }
    
   
    void setInputData(List<String[]> inputData) {
        this.input = inputData;
    }
}

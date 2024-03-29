package Controller;

import DAO.NutritionInformationDao;
import DAO.OrderedListDao;
import DAO.RecipieDao;
import DAO.UserDao;
import POJO.NutritionInformation;
import POJO.OrderedList;
import POJO.Recipie;
import POJO.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

@Controller
public class RecipieController {
    @Autowired
    UserDao userDao;
    @Autowired
    NutritionInformationDao nutritionInformationDao;
    @Autowired
    OrderedListDao orderedListDao;
    @Autowired
    RecipieDao recipieDao;

    @RequestMapping(value="/v1/recipie/{id}",method = RequestMethod.PUT, consumes = "application/json")
    public @ResponseBody
    ResponseEntity<String>
    updateRecipie(@RequestHeader(value="Authorization") String auth, @RequestBody ObjectNode objectNode, @PathVariable("id") String id){
        byte[] decodedBytes = Base64.getDecoder().decode(auth.split("Basic ")[1]);
        String decodedString = new String(decodedBytes);
        String email = decodedString.split(":")[0];
        String password = decodedString.split(":")[1];
        User user = userDao.getUserInfo(email);
        //find recipie which need to be updated
        Recipie recipie_updated = recipieDao.getRecipieInfo(id);
        if(!Authentication(email, password)) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "email and password is not matching");
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(jObject.toString());
        }
        if(recipie_updated == null){
            JSONObject jObject = new JSONObject();
            jObject.put("message", "Recipie with id " + id + " does not exist");
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(jObject.toString());
        }
        if(!user.getId().equals(recipie_updated.getAuthorId())) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "you're not authorized to update this recipie");
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(jObject.toString());
        }
        String missing_field = checkRequiredInput(objectNode);
        if (missing_field != null) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", missing_field + " is missing");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }

        boolean isCookMatch = inputIntegerCheck(objectNode.get("cook_time_in_min").asText());
        boolean isPrepMatch =inputIntegerCheck(objectNode.get("prep_time_in_min").asText());
        boolean isServingsMatch = inputIntegerCheck(objectNode.get("servings").asText());
        if (!(isCookMatch && isPrepMatch && isServingsMatch)) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "the format of cook time, prep time or servings is wrong, should be integer");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }

        int cook_time = objectNode.get("cook_time_in_min").asInt();
        int prep_time = objectNode.get("prep_time_in_min").asInt();
        if (cook_time % 5 != 0 || prep_time % 5 != 0) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "cook time and prep time should be multiple of 5");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }
        recipie_updated.setCookTimeInMin(cook_time);
        recipie_updated.setPrepTimeInMin(prep_time);
        recipie_updated.setTotalTimeInMin(cook_time + prep_time);

        recipie_updated.setTitle(objectNode.get("title").asText());
        recipie_updated.setCusine(objectNode.get("cusine").asText());

        int servings = objectNode.get("servings").asInt();
        if (servings < 1 || servings > 5) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "the value of servings has to be in the range of [1,5]");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }
        recipie_updated.setServings(servings);

        //set updated Time
        Date dNow = new Date( );
        SimpleDateFormat ft = new SimpleDateFormat ("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        recipie_updated.setUpdatedTs(ft.format(dNow));

        //update_ingredients
        List<String> ingredients = new ArrayList<>();
        JsonNode str = objectNode.get("ingredients");
        if(!str.isArray() || str.size() ==0 ) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "the value of ingredients should be an array with length > 0");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }
        for(JsonNode ingredient : str){
            String str_ingredient = ingredient.toString();
            ingredients.add(str_ingredient.substring(1, str_ingredient.length() - 1));
        }
        recipie_updated.setIngredients(ingredients);

        //orderedList
        List<OrderedList> orderedLists = orderedListDao.getOrderedList(id);
        try {
            ArrayNode arrayNode = objectNode.withArray("steps");
            if (arrayNode.size()==0) {
                JSONObject jObject = new JSONObject();
                jObject.put("message", "the steps should not be null");
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(jObject.toString());
            }
            List<OrderedList> new_orderedLists = new LinkedList<>();
            //updated former_orderList.size() >= updated_orderList.size()
            if (orderedLists.size() >= arrayNode.size()) {
                int i = 0;
                while ( i < orderedLists.size()) {
                    if(i < arrayNode.size()){
                        JsonNode jsonNode = arrayNode.get(i);
                        boolean isPositionMatch = inputIntegerCheck(jsonNode.get("position").asText());
                        if (!isPositionMatch) {
                            JSONObject jObject = new JSONObject();
                            jObject.put("message", "the format of position is wrong, should be integer");
                            return ResponseEntity
                                    .status(HttpStatus.BAD_REQUEST)
                                    .body(jObject.toString());
                        }
                        int position = jsonNode.get("position").asInt();
                        if (position < 1) {
                            JSONObject jObject = new JSONObject();
                            jObject.put("message", "the value of position has to be larger than 1");
                            return ResponseEntity
                                    .status(HttpStatus.BAD_REQUEST)
                                    .body(jObject.toString());
                        }
                        orderedLists.get(i).setPosition(position);
                        orderedLists.get(i).setItems(jsonNode.get("items").asText());
                        orderedListDao.update(orderedLists.get(i));
                    }else {
                        orderedListDao.delete(orderedLists.get(i));
                    }
                    i++;
                }
            } else {
                int i =0;
                while (i < arrayNode.size()){
                    if (i < orderedLists.size()) {
                        JsonNode jsonNode = arrayNode.get(i);
                        boolean isPositionMatch = inputIntegerCheck(jsonNode.get("position").asText());
                        if (!isPositionMatch) {
                            JSONObject jObject = new JSONObject();
                            jObject.put("message", "the format of position is wrong, should be integer");
                            return ResponseEntity
                                    .status(HttpStatus.BAD_REQUEST)
                                    .body(jObject.toString());
                        }
                        int position = jsonNode.get("position").asInt();
                        if (position < 1) {
                            JSONObject jObject = new JSONObject();
                            jObject.put("message", "the value of position has to be larger than 1");
                            return ResponseEntity
                                    .status(HttpStatus.BAD_REQUEST)
                                    .body(jObject.toString());
                        }
                        orderedLists.get(i).setPosition(position);
                        orderedLists.get(i).setItems(jsonNode.get("items").asText());
                        orderedListDao.update(orderedLists.get(i));
                    } else {
                        OrderedList orderedList = new OrderedList();
                        boolean isPositionMatch = inputIntegerCheck(arrayNode.get(i).get("position").asText());
                        if (!isPositionMatch) {
                            JSONObject jObject = new JSONObject();
                            jObject.put("message", "the format of position is wrong, should be integer");
                            return ResponseEntity
                                    .status(HttpStatus.BAD_REQUEST)
                                    .body(jObject.toString());
                        }
                        int position = arrayNode.get(i).get("position").asInt();
                        if (position < 1) {
                            JSONObject jObject = new JSONObject();
                            jObject.put("message", "the value of position has to be larger than 1");
                            return ResponseEntity
                                    .status(HttpStatus.BAD_REQUEST)
                                    .body(jObject.toString());
                        }
                        orderedList.setPosition(position);
                        orderedList.setItems(arrayNode.get(i).get("items").asText());
                        orderedList.setRecipie(recipie_updated);
                        new_orderedLists.add(orderedList);
                    }
                    i++;
                }
                if(new_orderedLists.size() > 0){
                    recipie_updated.setSteps(new_orderedLists);
                }
            }
            //        recipieDao.update(recipie_updated);
            if (new_orderedLists.size() > 0){
                for(OrderedList ol : new_orderedLists){
                    ol.setRecipie(recipie_updated);
                    orderedListDao.save(ol);
                }
            }
        } catch (Exception e){
            JSONObject jObject = new JSONObject();
            jObject.put("message", "the format of steps is wrong, should be array");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }
        //NutritionInforamtion update
        NutritionInformation nuInfo = nutritionInformationDao.get(id);
//        JsonNode nutrition_information = objectNode.get("nutrition_information");
        try{
            ObjectNode nutrition_information = objectNode.with("nutrition_information");
            String field = checkNutritionInput(nutrition_information);
            if (field != null) {
                JSONObject jObject = new JSONObject();
                jObject.put("message", field + " is missing");
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(jObject.toString());
            }

            // input check
            boolean check = inputIntegerCheck(nutrition_information.get("calories").asText()) &&
                    inputFloatCheck(nutrition_information.get("carbohydrates_in_grams").asText()) &&
                    inputFloatCheck(nutrition_information.get("cholesterol_in_mg").asText()) &&
                    inputIntegerCheck(nutrition_information.get("sodium_in_mg").asText()) &&
                    inputFloatCheck(nutrition_information.get("protein_in_grams").asText());
            if (!check) {
                JSONObject jObject = new JSONObject();
                jObject.put("message", "the format of nutrition information is wrong");
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(jObject.toString());
            }

            nuInfo.setCalories(nutrition_information.get("calories").asInt());
            nuInfo.setCarbohydratesInGrams(nutrition_information.get("carbohydrates_in_grams").asDouble());
            nuInfo.setCholesterolInMg(nutrition_information.get("cholesterol_in_mg").asDouble());
            nuInfo.setSodiumInMg(nutrition_information.get("sodium_in_mg").asInt());
            nuInfo.setProteinInGrams(nutrition_information.get("protein_in_grams").asDouble());
        } catch (Exception e) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "the format of nutrition information should be a pojo");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }

        recipie_updated.setNutritionInformation(nuInfo);
        nutritionInformationDao.update(nuInfo);
        recipieDao.update(recipie_updated);

        JSONObject jObject = recipieParser(recipie_updated);
        return ResponseEntity.status(HttpStatus.OK).
                body(jObject.toString());
    }

    @RequestMapping(value = "/v1/recipie/",method = RequestMethod.POST,consumes = "application/json")
    public @ResponseBody
    ResponseEntity<String> createRecipie(@RequestHeader(value="Authorization") String auth ,@RequestBody ObjectNode objectNode){

        byte[] decodedBytes = Base64.getDecoder().decode(auth.split("Basic ")[1]);
        String decodedString = new String(decodedBytes);
        String email = decodedString.split(":")[0];
        String password = decodedString.split(":")[1];

        if(!Authentication(email,password)){
            JSONObject jObject = new JSONObject();
            jObject.put("message", "email and password is not matching");
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(jObject.toString());
        }

        String missing_field = checkRequiredInput(objectNode);
        if (missing_field != null) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", missing_field + " is missing");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }

        Recipie recipie = new Recipie();

        boolean isCookMatch = inputIntegerCheck(objectNode.get("cook_time_in_min").asText());
        boolean isPrepMatch =inputIntegerCheck(objectNode.get("prep_time_in_min").asText());
        boolean isServingsMatch = inputIntegerCheck(objectNode.get("servings").asText());
        if (!(isCookMatch && isPrepMatch && isServingsMatch)) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "the format of cook time, prep time or servings is wrong, should be integer");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }

        int cook_time = objectNode.get("cook_time_in_min").asInt();
        int prep_time = objectNode.get("prep_time_in_min").asInt();
        if (cook_time % 5 != 0 || prep_time % 5 != 0) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "cook time and prep time should be multiple of 5");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }

        recipie.setCookTimeInMin(cook_time);
        recipie.setPrepTimeInMin(prep_time);
        recipie.setTotalTimeInMin(cook_time + prep_time);

        recipie.setTitle(objectNode.get("title").asText());
        recipie.setCusine(objectNode.get("cusine").asText());

        int servings = objectNode.get("servings").asInt();
        if (servings < 1 || servings > 5) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "the value of servings has to be in the range of [1,5]");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }
        recipie.setServings(servings);

        recipie.setId(UUID.randomUUID().toString());
        //set Author id
        String userId = userDao.getUserInfo(email).getId();
        recipie.setAuthorId(userId);

        //set ingredients
        List<String> ingredientsList = new ArrayList<>();
        JsonNode str = objectNode.get("ingredients");
        if(!str.isArray() || str.size() ==0 ) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "the value of ingredients should be an array with length > 0");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }
        for(JsonNode ingredient : str){
            String str_ingredient = ingredient.toString();
            ingredientsList.add(str_ingredient.substring(1, str_ingredient.length() - 1));
        }
        recipie.setIngredients(ingredientsList);

        //set Time
        Date dNow = new Date( );
        SimpleDateFormat ft = new SimpleDateFormat ("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        recipie.setCreatedTs(ft.format(dNow));
        recipie.setUpdatedTs(ft.format(dNow));

        // set Steps
        List<OrderedList> orderedLists = new LinkedList<>();
        try{
            ArrayNode arrayNode = objectNode.withArray("steps");
            if (arrayNode.size()==0) {
                JSONObject jObject = new JSONObject();
                jObject.put("message", "the steps should not be null");
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(jObject.toString());
            }
            for(JsonNode jsonNode : arrayNode){
                OrderedList orderedList = new OrderedList();
                boolean isPositionMatch = inputIntegerCheck(jsonNode.get("position").asText());
                if (!isPositionMatch) {
                    JSONObject jObject = new JSONObject();
                    jObject.put("message", "the format of position is wrong, should be integer");
                    return ResponseEntity
                            .status(HttpStatus.BAD_REQUEST)
                            .body(jObject.toString());
                }
                int position = jsonNode.get("position").asInt();
                if (position < 1) {
                    JSONObject jObject = new JSONObject();
                    jObject.put("message", "the value of position has to be larger than 1");
                    return ResponseEntity
                            .status(HttpStatus.BAD_REQUEST)
                            .body(jObject.toString());
                }
                orderedList.setPosition(position);
                orderedList.setItems(jsonNode.get("items").asText());
                orderedList.setRecipie(recipie);
                orderedLists.add(orderedList);
            }
        }catch (Exception e){
            JSONObject jObject = new JSONObject();
            jObject.put("message", "the format of steps is wrong, should be array");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }
        recipie.setSteps(orderedLists);

        //setNutrition
        NutritionInformation nutritionInformation = new NutritionInformation();
        try{
            ObjectNode nutritionInformationObjectNode = objectNode.with("nutrition_information");
            String field = checkNutritionInput(nutritionInformationObjectNode);
            if (field != null) {
                JSONObject jObject = new JSONObject();
                jObject.put("message", field + " is missing");
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(jObject.toString());
            }

            // input check for nutrition info
            boolean check = inputIntegerCheck(nutritionInformationObjectNode.get("calories").asText()) &&
                    inputFloatCheck(nutritionInformationObjectNode.get("carbohydrates_in_grams").asText()) &&
                    inputFloatCheck(nutritionInformationObjectNode.get("cholesterol_in_mg").asText()) &&
                    inputIntegerCheck(nutritionInformationObjectNode.get("sodium_in_mg").asText()) &&
                    inputFloatCheck(nutritionInformationObjectNode.get("protein_in_grams").asText());
            if (!check) {
                JSONObject jObject = new JSONObject();
                jObject.put("message", "the format of nutrition information is wrong");
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(jObject.toString());
            }

            nutritionInformation.setCalories(nutritionInformationObjectNode.get("calories").asInt());
            nutritionInformation.setCholesterolInMg(nutritionInformationObjectNode.get("cholesterol_in_mg").asInt());
            nutritionInformation.setSodiumInMg(nutritionInformationObjectNode.get("sodium_in_mg").asInt());
            nutritionInformation.setCarbohydratesInGrams(nutritionInformationObjectNode.get("carbohydrates_in_grams").asDouble());
            nutritionInformation.setProteinInGrams(nutritionInformationObjectNode.get("protein_in_grams").asDouble());
            nutritionInformation.setRecipie(recipie);

        }catch (Exception e) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "the format of nutrition information should be a pojo");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }

        recipie.setNutritionInformation(nutritionInformation);
        recipieDao.save(recipie);
        for(OrderedList ol : orderedLists){
            orderedListDao.save(ol);
        }
        nutritionInformationDao.save(nutritionInformation);

        JSONObject jObject = recipieParser(recipie);

        return ResponseEntity.status(HttpStatus.CREATED).
                body(jObject.toString());
    }

    public boolean Authentication(String userName, String password) {
        if(userDao.getUserInfo(userName)==null) return false;
        String stored_hash = userDao.getUserInfo(userName).getPassword();
        if (BCrypt.checkpw(password, stored_hash)) {
            return true;
        } else{
            return false;
        }
    }

    @RequestMapping(value = "/v1/recipie/*",method = RequestMethod.DELETE)
    public @ResponseBody
    ResponseEntity<String> deleteRecipie(@RequestHeader(value="Authorization") String auth, HttpServletRequest request){
        byte[] decodedBytes = Base64.getDecoder().decode(auth.split("Basic ")[1]);
        String decodedString = new String(decodedBytes);
        String email = decodedString.split(":")[0];
        String password = decodedString.split(":")[1];
        String[] URI = request.getRequestURI().split("/");
        if (URI.length == 4) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "recipie id is required");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(jObject.toString());
        }
        Recipie recipie = recipieDao.getRecipieInfo(URI[4]);
        User user = userDao.getUserInfo(email);
        if(!Authentication(email, password)) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "email and password is not matching");
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(jObject.toString());
        }
        if(recipie == null){
            JSONObject jObject = new JSONObject();
            jObject.put("message", "Recipie with id " + URI[4] + " does not exist");
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(jObject.toString());
        }
        if(!user.getId().equals(recipie.getAuthorId())) {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "you're not authorized to delete this recipie");
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(jObject.toString());
        }
        recipieDao.delete(recipie);
        JSONObject jObject = new JSONObject();
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(jObject.toString());
    }

    //get Recipie information
    @RequestMapping(value="/v1/recipie/{id}", method = RequestMethod.GET)
    public @ResponseBody
    ResponseEntity<String> getRecipie(@PathVariable("id") String id){
        Recipie recipie = recipieDao.getRecipieInfo(id);
        if(recipie != null) {
            JSONObject jObject = recipieParser(recipie);

            return ResponseEntity.status(HttpStatus.OK).
                    body(jObject.toString());
        }
        else {
            JSONObject jObject = new JSONObject();
            jObject.put("message", "Unable to get recipie info with id " + id);
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(jObject.toString());
        }
    }

    private JSONObject recipieParser(Recipie recipie) {
        JSONObject jObject = new JSONObject();
        jObject.put("id", recipie.getId());
        jObject.put("created_ts", recipie.getCreatedTs());
        jObject.put("updated_ts", recipie.getUpdatedTs());
        jObject.put("author_id", recipie.getAuthorId());
        jObject.put("cook_time_in_min", recipie.getCookTimeInMin());
        jObject.put("prep_time_in_min", recipie.getPrepTimeInMin());
        jObject.put("total_time_in_min", recipie.getTotalTimeInMin());
        jObject.put("title", recipie.getTitle());
        jObject.put("cusine", recipie.getCusine());
        jObject.put("servings", recipie.getServings());
        jObject.put("ingredients", new JSONArray(recipie.getIngredients()));

        JSONArray steps = new JSONArray();
        for (OrderedList ol : orderedListDao.getOrderedList(recipie.getId())) {
            JSONObject orderedList = new JSONObject();
            orderedList.put("position", ol.getPosition());
            orderedList.put("items", ol.getItems());
            steps.put(orderedList);
        }
        jObject.put("steps", steps);

        JSONObject nuinfo = new JSONObject();
        NutritionInformation n = recipie.getNutritionInformation();
        nuinfo.put("calories", n.getCalories());
        nuinfo.put("cholesterol_in_mg", n.getCholesterolInMg());
        nuinfo.put("sodium_in_mg", n.getSodiumInMg());
        nuinfo.put("carbohydrates_in_grams", n.getCarbohydratesInGrams());
        nuinfo.put("protein_in_grams", n.getProteinInGrams());
        jObject.put("nutrition_information", nuinfo);

        return jObject;
    }

    public boolean inputIntegerCheck(String input) {
        String intPattern = "^[1-9]\\d*$";
        return Pattern.matches(intPattern, input);
    }

    public boolean inputFloatCheck(String input) {
        String floatPattern = "^([1-9]*[1-9][0-9]*(\\.[0-9]+)?|[0]+\\.[0-9]*[1-9][0-9]*)$";
        return Pattern.matches(floatPattern, input);
    }

    private String checkRequiredInput(ObjectNode objectNode) {
        Iterator<String> fieldNames = objectNode.fieldNames();
        List<String> requiredFields = new ArrayList<String>();
        List<String> inputFields = new ArrayList<String>();
        requiredFields.add("cook_time_in_min");
        requiredFields.add("prep_time_in_min");
        requiredFields.add("title");
        requiredFields.add("cusine");
        requiredFields.add("servings");
        requiredFields.add("ingredients");
        requiredFields.add("steps");
        requiredFields.add("nutrition_information");

        while(fieldNames.hasNext()) {
            String field = fieldNames.next();
            inputFields.add(field);
        }
        for (String field : requiredFields) {
            if (!inputFields.contains(field)) {
                return field;
            }
        }
        return null;
    }

    private String checkNutritionInput(ObjectNode objectNode) {
        Iterator<String> fieldNames = objectNode.fieldNames();
        List<String> requiredFields = new ArrayList<String>();
        List<String> inputFields = new ArrayList<String>();
        requiredFields.add("calories");
        requiredFields.add("cholesterol_in_mg");
        requiredFields.add("sodium_in_mg");
        requiredFields.add("carbohydrates_in_grams");
        requiredFields.add("protein_in_grams");

        while(fieldNames.hasNext()) {
            String field = fieldNames.next();
            inputFields.add(field);
        }
        for (String field : requiredFields) {
            if (!inputFields.contains(field)) {
                return field;
            }
        }
        return null;
    }
}

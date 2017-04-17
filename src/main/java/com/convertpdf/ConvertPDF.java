package main.java.com.convertpdf;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import main.java.com.salesforce.*;

import org.apache.commons.codec.binary.Base64;
import com.google.gson.Gson;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;

public class ConvertPDF extends HttpServlet{
	private SalesforceConnector sc;
	Map<String,String> params = new HashMap<String,String>();
	
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws  IOException {
		// get params from request body
		String paramStr = getBody(req);
		System.out.println("Incoming Request => "+paramStr);
		Gson gson = new Gson();
		Map<String,String> parameters = new HashMap<String,String>();		
		parameters = (HashMap<String,String>) gson.fromJson(paramStr, params.getClass());
		parameters.putAll(CredentialManager.getLogin());
		this.params = parameters;
		System.out.println("Parsing request body parames...");	
		
		// login to salesforce and pull attachment
		sc = new SalesforceConnector(params.get("Username"),params.get("Password"),params.get("environment"));
		ArrayList<SObject> attachments = new ArrayList<SObject>();		
		try {	
			System.out.println("logging into salesforce...");
			sc.login();
			attachments = query(params.get("attId"));
			System.out.println("queried "+attachments.size()+" attachments");	
		} catch (ConnectionException e1) {
			e1.printStackTrace();
		}
		
		// encrypt file
		ArrayList<SObject> encryptedSObjs = new ArrayList<SObject>();
		ArrayList<File> encryptedFiles = new ArrayList<File>();
		System.out.println("encrypting attachment...");
		for(SObject so : attachments){			
			try {
				EncryptFile ef = new EncryptFile();
				File fileEncrypt = ef.encrypt(base64ToByte((String)so.getField("Body")),(String)so.getField("Name"));							
				encryptedSObjs.add(fileToSObj((String)so.getField("ParentId"),(String)so.getField("Name")+".pgp",fileEncrypt));
				encryptedFiles.add(fileEncrypt);
			} catch (Exception e) {
				e.printStackTrace();
			}	
		}
		
		// add attachment to report in salesforce
		System.out.println("adding salesforce attachment...");
		try {
			sc.create(encryptedSObjs);
		} catch (ConnectionException e) {
			e.printStackTrace();
		}

		// upload files to ida ftp		
		UploadFile uf = new UploadFile();
		uf.start(params, encryptedFiles);
		uf.upload();
	}
	
	private SObject fileToSObj(String pId, String fileName, File theFile){
		SObject sObj = new SObject("Attachment");
		sObj.setField("ParentId", pId);
		sObj.setField("Name", fileName);
		byte[] body = null;
		try {
			body = Files.readAllBytes(theFile.toPath());
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		sObj.setField("Body", body);
		return sObj;
	}
	
	private String getBody(HttpServletRequest req) throws IOException{
		BufferedReader br = req.getReader();
		StringBuilder sb = new StringBuilder();  
		String str;
	    while( (str = br.readLine()) != null ){
	        sb.append(str);
		 } 
	    return sb.toString().isEmpty() ? "{}" : sb.toString();
	}
	
	private ArrayList<SObject> query(String ids) throws ConnectionException{
		System.out.println("querying salesforce...");
		String idFilter;
		if(ids.contains(",")){
			String[] idParts = ids.split(",");
			idFilter = " Id IN(";
			for(String attId : idParts){
				if(idFilter == " Id IN("){
					idFilter = idFilter + "'" + attId + "'";
				}else {
					idFilter = idFilter + ",'" + attId + "'";
				}
			}
			idFilter = idFilter + ")";
		} else {
			idFilter = " Id = '"+ids+"'";
		}
		String soql = "SELECT Id, Name, ParentId, Body FROM Attachment WHERE "+idFilter;
		return sc.query(soql);
	}
	
	public byte[] base64ToByte(String data) throws Exception {
		return Base64.decodeBase64(data.getBytes());
	}
}
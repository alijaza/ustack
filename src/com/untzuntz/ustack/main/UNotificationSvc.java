package com.untzuntz.ustack.main;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javapns.Push;
import javapns.communication.exceptions.CommunicationException;
import javapns.communication.exceptions.KeystoreException;
import javapns.devices.Device;
import javapns.notification.PushNotificationPayload;
import javapns.notification.PushedNotification;
import javapns.notification.ResponsePacket;
import javapns.notification.transmission.PushQueue;

import javax.mail.internet.AddressException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.log4j.Logger;

import com.mongodb.BasicDBList;
import com.mongodb.DBObject;
import com.twilio.sdk.TwilioRestClient;
import com.twilio.sdk.TwilioRestResponse;
import com.untzuntz.ustack.data.NotificationInst;
import com.untzuntz.ustack.data.NotificationTemplate;
import com.untzuntz.ustack.data.PushQueueInstance;
import com.untzuntz.ustack.util.BasicUtils;

public class UNotificationSvc {

	protected static Logger logger = Logger.getLogger(UNotificationSvc.class);
	private static final Hashtable<String,PushQueue> iosPushQueues = new Hashtable<String,PushQueue>();
	
	private boolean testMode;
	private Hashtable<String,DBObject> supportingData;
	private Hashtable<String,File> attachments;
	private List<String> skips;
	
	public static void main(String[] args) throws Exception {

		if (args.length < 5)
		{
			System.err.println("Usage: com.untzuntz.ustack.main.UNotificationSvc -addpushqueue [appname] [queuename] [filename] [password]");
			return;
		}
		
		if (args[0].equalsIgnoreCase("-addpushqueue"))
		{
			UOpts.setAppName(args[1]);
			PushQueueInstance pushQ = PushQueueInstance.createKeyStore(args[2], new FileInputStream(args[3]), args[4]);
			pushQ.save("System");
		}
	}
	
	public UNotificationSvc()
	{
		this.attachments = new Hashtable<String,File>();
		this.supportingData = new Hashtable<String,DBObject>();
		this.skips = new Vector<String>();
	}
	
	public static KeyStore loadKeystore(InputStream keystoreStream, char[] password) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
		
		KeyStore keyStore;
		keyStore = KeyStore.getInstance("PKCS12");
		keyStore.load(keystoreStream, password);
		return keyStore;
	}
	
	public static PushQueue getIOSPushQueue(String name) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException, KeystoreException
	{
		if (name == null)
			return null;
		
		PushQueue queue = iosPushQueues.get(name);
		if (queue == null)
		{
			PushQueueInstance ksd = PushQueueInstance.getByName(name);
			if (ksd != null)
			{
		        String password = ksd.getPassword();
		        
		        logger.info("Loading Push Queue [" + name + "]");
		        
		        queue = Push.queue(loadKeystore(new ByteArrayInputStream(ksd.getBinaryData()), password.toCharArray()), password, ksd.isProduction(), ksd.getThreads());
		        queue.start();
		        
		        iosPushQueues.put(name, queue);
			}
			else
				logger.error("Failed to find ios push queue config => " + name);
		}
		else
	        logger.info("Found Push Queue [" + name + "]");
		
		return queue;
	}
	
	/**
	 * A skip will tell the notification to run all the notifications except those that match in the parameters provided.
	 * 
	 * Example:
	 * 
	 * A site has 3 users subscribed to it's new.document notification event
	 * 
	 * User #1 of the site initiated the new.document so he/she doesn't need to be alerted
	 * 
	 * To do this, add user #1 to the skip list.
	 * 
	 * 
	 * SiteId's are also supported
	 * 
	 * @param skip
	 */
	public void addSkip(String skip)
	{
		if (skip == null)
			return;
		
		skips.add(skip);
	}
	
	/**
	 * Sets an attachment. The provided name is used to name the file
	 * @param name
	 * @param location
	 * @return
	 */
	public UNotificationSvc setAttachment(String name, File location) {
		
		if (name == null)
			return this;
			
		if (location == null)
			attachments.remove(name);
		else
			attachments.put(name, location);
		
		return this;
	}
	
	/** Set objects for use in the template */
	public UNotificationSvc setData(String name, DBObject data)
	{
		if (name == null)
			return this;
		
		if (data == null)
			supportingData.remove(name);
		else
			supportingData.put(name, data);	
		return this;
	}
	
	public void setTestMode(boolean mode)
	{
		testMode = mode;
	}

	/** Execute Notifications */
	public int notify(String eventName, DBObject search)
	{
		int alertsSent = 0;
		
		NotificationTemplate template = NotificationTemplate.getNotificationTemplate(eventName);
		if (template == null)
			return -1;
		
		List<NotificationInst> notiList = NotificationInst.getNotifications(eventName, search);
		for (NotificationInst notif : notiList)
		{
			BasicDBList recvTypes = notif.getTypeList();
			for (int i = 0; i < recvTypes.size(); i++)
			{
				DBObject type = (DBObject)recvTypes.get(i);
				// check if the template has the same type (or name)
				DBObject templateTypeCheck = template.getType( (String)type.get("name") );
				if (templateTypeCheck != null)
				{
					// send the actual notification
					if (sendNotification(notif, template, type))
						alertsSent++;
				}
				else
					logger.warn(notif.getNotificationId() + " has template type '" + type.get("name") + "' but the template (" + eventName + ") does not.");
			}
		}
		
		return alertsSent;
	}

	/**
	 * Actually send the notification - by known type then by an interface
	 * @param template
	 */
	private boolean sendNotification(NotificationInst notif, NotificationTemplate template, DBObject endpointConfig)
	{
		String type = (String)endpointConfig.get("name");
		
		for (String skip : skips)
		{
			if (skip.equalsIgnoreCase((String)endpointConfig.get("userName")))
			{
				logger.info("Skipping Notification for user '" + skip + "' <- Configured");
				return false;
			}
			else if (skip.equalsIgnoreCase((String)endpointConfig.get("siteId")))
			{
				logger.info("Skipping Notification for site id '" + skip + "' <- Configured");
				return false;
			}
		}
		
		if ("email".equalsIgnoreCase(type))
			return sendEmail(notif, template, endpointConfig);
		else if ("sms".equalsIgnoreCase(type))
			return sendSMS(notif, template, endpointConfig);
		else if ("ios-push".equalsIgnoreCase(type))
			return sendIOSPush(notif, template, endpointConfig);
		else if ("facebook".equalsIgnoreCase(type))
			return sendFacebook(notif, template, endpointConfig);
		else
		{
			// TODO: Implement custom interface
			logger.warn("Unknown template type '" + type + "'");
		}
		return false;
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public boolean sendFacebook(NotificationInst notif, NotificationTemplate template, DBObject endpointConfig)
	{
		if (notif != null && notif.get("invalid") != null)
		{
			logger.info("Skipping Facebook Delivery for notification id [" + notif.getNotificationId() + "] => Reason: " + notif.get("invalid"));
			return false;
		}
		
		DBObject templ = template.getType("facebook");
		if (templ == null)
		{
			if (notif == null)
				return false;
			
			logger.info("Unknown template type [facebook] for notification id [" + notif.getNotificationId() + "]");
			return false;
		}
		
		DefaultHttpClient client = null;
		try {
			
			String alert = processTemplate( (String)templ.get("templateText"), notif );
			String destination = (String)endpointConfig.get("destination");
					
			if (destination == null && templ.get("postTo") != null)
				destination = processTemplate( (String)templ.get("postTo"), notif);
			if (destination == null)
				destination = "me";
			
			String token = (String)endpointConfig.get("token");
			
			// message=Play%20me%20in%20TWF%20now!&name=Join%20Now&caption=TWF&description=TWF%20Login&link=http://google.com?uid=1234
			List qparams = new ArrayList();
			
			qparams.add(new BasicNameValuePair("message", alert));
			qparams.add(new BasicNameValuePair("name",  (String)templ.get("linkName") ));
			qparams.add(new BasicNameValuePair("caption",  (String)templ.get("caption") ));
			qparams.add(new BasicNameValuePair("description",  (String)templ.get("description") ));
			qparams.add(new BasicNameValuePair("link",  processTemplate((String)templ.get("link"), notif) ));
			qparams.add(new BasicNameValuePair("picture",  (String)templ.get("picture") ));
			qparams.add(new BasicNameValuePair("access_token", token));
	
			URI uri = URIUtils.createURI("https", "graph.facebook.com", 443, "/" + destination + "/feed", null, null);
			logger.info("URL: " + uri);
			
			client = new DefaultHttpClient();  
			HttpPost post = new HttpPost(uri);
			post.setEntity(new UrlEncodedFormEntity(qparams)); // setup parameters
	
			HttpResponse response = client.execute(post);

			qparams.clear();
			
			if (response.getStatusLine().getStatusCode() == 200)
				return true;

			HttpEntity e = response.getEntity();
			Writer writer = BasicUtils.getResponseString(e);
			
			if (writer.toString().indexOf("Session has expired at unix time") > -1)
			{
				
			}
			else
			{
				logger.error("Response Specifics: " + writer.toString());
				if (notif != null)
					notif.disable("Invalid response from Facebook API");
			}
			
		} catch (Exception er) {
			logger.warn("Failed to deliver notification to Facebook", er);
			close(client);
			return false;
		} finally {
			close(client);
		}
		
		return true;
	}
	
	private void close(DefaultHttpClient client) 
	{
		if (client == null)
			return;
		
		try {
	        client.getConnectionManager().shutdown();     
		} catch (Exception e) {}
	}

	public boolean sendIOSPush(NotificationInst notif, NotificationTemplate template, DBObject endpointConfig)
	{
		if (notif.get("invalid") != null)
		{
			logger.info("Skipping iOS push for notification id [" + notif.getNotificationId() + "] => Reason: " + notif.get("invalid"));
			return false;
		}
		
		DBObject templ = template.getType("ios-push");
		if (templ == null)
		{
			logger.info("Unknown template type [ios-push] for notification id [" + notif.getNotificationId() + "]");
			return false;
		}

		String alert = processTemplate( (String)templ.get("templateText"), notif );
		String iosPushQueueName = (String)templ.get("iosPushQueueName");
		String destination = (String)endpointConfig.get("destination");
		
		return sendIOSPush(destination, alert, iosPushQueueName, notif);
	}
	
	public boolean sendIOSPush(String destination, String alert, String iosPushQueueName, NotificationInst notif)
	{		
		if (iosPushQueueName == null)
		{
			logger.error("Invalid iosPushQueueName value - null!");
			return false;
		}
		
		if (destination.startsWith("<"))
		{
			destination = destination.substring(1, destination.length() - 1);
			destination = destination.replaceAll(" ", "");
			logger.info("Adjust Device ID: " + destination);
		}
		
		boolean ret = false;
		
		try {
			
	        PushNotificationPayload payload = PushNotificationPayload.complex();
	        payload.addAlert(alert);
	        
	        PushQueue queue = getIOSPushQueue(iosPushQueueName);
	        if (queue != null)	
	        {
	        	logger.info("Adding payload to queue [" + iosPushQueueName + "]");
	        	queue.add(payload, destination);
	        	
	        }

//			List<PushedNotification> notifications = Push.alert(alert, "pushtest-push.p12", "Jedman123!", false, new String[] { destination }); 
//	
//			for (PushedNotification notification : notifications) 
//			{	
//			}	
	        
		} catch (KeystoreException e) {
            /* A critical problem occurred while trying to use your keystore */  
            logger.error("Error while communicating with iOS Push [CERT]", e);
	    } catch (Exception e) {
            logger.warn("Error while with iOS Push [OTHER]", e);
	    }			
		
		return ret;
	}
	
	private void handlePushedNotification(PushedNotification notification, NotificationInst notif)
	{
		if (!notification.isSuccessful()) 
		{
			String reason = null;
			String invalidToken = notification.getDevice().getToken();

            /* Find out more about what the problem was */  
            Exception theProblem = notification.getException();
            reason = theProblem.getMessage();
            logger.warn("Failed to push message to iOS device[" + invalidToken + "]", theProblem);

            /* If the problem was an error-response packet returned by Apple, get it */  
            ResponsePacket theErrorResponse = notification.getResponse();
            if (theErrorResponse != null) {
            	reason = theErrorResponse.getMessage();
                logger.warn("Failed to push message to iOS device[" + invalidToken + "] => " + reason);
            }
            
            if (notif != null)
            	notif.disable(reason);
		}

	}

	/** Send an Email */
	public boolean sendEmail(NotificationInst notif, NotificationTemplate template, DBObject endpointConfig)
	{
		DBObject typeData = template.getType("email");
//		String emailToName = (String)endpointConfig.get("destinationName");
		String emailToAddr = (String)endpointConfig.get("destination");
		String emailFromName = processTemplate( (String)typeData.get("fromName"), notif );
		String emailFromAddr = processTemplate( (String)typeData.get("fromAddress"), notif );
		String subject = processTemplate( (String)typeData.get("subject"), notif );
		String emailBody = processTemplate( (String)typeData.get("templateText"), notif );
		String htmlEmailBody = null;
		if (typeData.get("htmlTemplateText") != null)
			htmlEmailBody = processTemplate( (String)typeData.get("htmlTemplateText"), notif );
		
		if (emailToAddr == null)
		{
			logger.warn("Invalid Email Destination (null): " + notif);
			return false;
		}

		if (testMode)
			logger.info("TESTMODE ==> From: " + emailFromAddr + " | To: " + emailToAddr + " | Subj: " + subject + " ==> " + emailBody);
		else
		{
			try {
				Emailer.postMail(emailToAddr, emailFromAddr, emailFromName, subject, emailBody, htmlEmailBody, attachments);
			} catch (AddressException err) {
				logger.error("Invalid Address : " + err);
				return false;
			}
		}
		
		return true;
	}
	
	/**
	 * Clears out spaces and dashes from a phone number.
	 * @param toPhone
	 * @return
	 */
	public static String trimPhoneNumber(String toPhone) {
		
		if (toPhone == null)
			return null;

		toPhone = toPhone.replaceAll(" ", "");
		toPhone = toPhone.replaceAll("-", "");

		return toPhone;
	}

	/** Send an SMS */
	public boolean sendSMS(NotificationInst notif, NotificationTemplate template, DBObject endpointConfig)
	{
		String smsMsg = processTemplate( (String)template.getType("sms").get("templateText"), notif );
		String destination = (String)endpointConfig.get("destination");
		String toPhone = destination;

    	try {
			// open secure connection
    		TwilioRestClient client = new TwilioRestClient(UOpts.getProperty("EvenFlow.Twillio.APIKey"), UOpts.getProperty("EvenFlow.Twillio.APIAuthToken"), null);

    		toPhone = trimPhoneNumber(toPhone);

    		if (toPhone.length() == 10 && !toPhone.startsWith("1"))
    			toPhone = "1" + toPhone;

    		if (!toPhone.startsWith("+"))
    			toPhone = "+" + toPhone;
			
			logger.info("Sending SMS [" + UOpts.getProperty("EvenFlow.Twillio.SMSPhoneNumber") + " => " + toPhone + "] // " + smsMsg + " via Twilio");

            Map<String,String> params = new HashMap<String,String>();
            params.put("From", UOpts.getProperty("EvenFlow.Twillio.SMSPhoneNumber"));
            params.put("To", toPhone);
            params.put("Body", smsMsg);
            TwilioRestResponse response = client.request("/2010-04-01/Accounts/" + client.getAccountSid() + "/SMS/Messages", "POST", params);
        
            if (response == null)
            	throw new Exception("Failed to send SMS via Twillio [To: " + toPhone + "]");
            else if (response.isError())
            	throw new Exception("Failed to send SMS via Twillio [To: " + toPhone + "] -> " + response.getHttpStatus() + " // " + response.getResponseText());

            logger.info("Twillio Response: " + response.getResponseText());
			
    	} catch (Exception er) {
    		// queue message?
    		logger.error("Failed to deliver SMS to [To: " + toPhone + "/" + destination + "]", er);
    	}

		
		return true;
	}

	/** Process Template Text */
	private String processTemplate(String text, DBObject other)
	{
		while (text != null && text.indexOf("${") > -1)
		{
			int start = text.indexOf("${");
			int end = text.indexOf("}", start + 1);
					
			String variable = text.substring(start + 2, end);
			String varValue = "";
			if (variable.indexOf(".") > -1)
			{
				String loca = variable.substring(0, variable.indexOf("."));
				DBObject obj = supportingData.get(loca);
				if (obj != null)
					varValue = "" + obj.get( variable.substring(variable.indexOf(".") + 1) );
			}
			else if (other != null)
				varValue = "" + other.get( variable );
			
			if (varValue == null || "null".equalsIgnoreCase(varValue))
				varValue = "";
			
			String newValue = text.substring(0, start);
			newValue += varValue;
			newValue += text.substring(end + 1, text.length());
			text = newValue;
		}
		
		return (text == null ? "" : text);
	}
	
	public static void processAppleFeedbackService()
	{
		try {
			List<Device> inactiveDevices = Push.feedback("pushtest-push.p12", "Jedman123!", false);
			logger.info(inactiveDevices.size() + " devices inactive - flushing...");
			for (Device dev : inactiveDevices)
			{
				List<NotificationInst> notifList = NotificationInst.getNotification("ios-push", dev.getDeviceId());
				for (NotificationInst notif : notifList)
                	notif.disable("Feedback Service Disabled");
			}

		} catch (KeystoreException e) {
            /* A critical problem occurred while trying to use your keystore */  
            logger.error("Error while communicating with iOS Push [CERT]", e);
	    } catch (CommunicationException e) {
	    	/* A critical communication error occurred while trying to contact Apple servers */  
            logger.warn("Error while communicating with iOS Push [COMMS]", e);
	    }			

	}
	
}
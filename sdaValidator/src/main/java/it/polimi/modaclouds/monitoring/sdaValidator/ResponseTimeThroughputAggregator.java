package it.polimi.modaclouds.monitoring.sdaValidator;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;

public class ResponseTimeThroughputAggregator {

	public static void main(String[] args) {
		Scanner input;
		int counter=0;
		String[] splitted;
		float partialSum=0;
		int currentHour;
		float hourlyThrougput;
		float hourlyResponseTime;

		
		try {
			input = new Scanner(new File("monitored_workload.out"));
			
			currentHour=1;
			
			while(input.hasNextLine())
			{
			   String line = input.nextLine();
			   splitted=line.split(",");
			   
			   int value=0;
			   
			   value=Integer.parseInt(splitted[3]);
			   
			   partialSum=partialSum+value;
			   
			   counter++;
			   
			   if(counter==360){
				  hourlyThrougput=partialSum/(3600);
				  System.out.println("current hour:"+currentHour+" hourly avarage throughtput: "+hourlyThrougput);
				  partialSum=0;
				  counter=0;
				  currentHour++;
			   }
			   
			   
			}
			
			
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		counter=0;
		partialSum=0;
		
		try {
			input = new Scanner(new File("monitored_responseTime.out"));
			
			currentHour=1;
			
			while(input.hasNextLine())
			{
			   String line = input.nextLine();
			   splitted=line.split(",");
			   
			   float value=0;
			   
			   value=Float.parseFloat(splitted[3]);
			   
			   partialSum=partialSum+value;
			   
			   counter++;
			   
			   if(counter==1825){
				  hourlyResponseTime=partialSum/counter;
				  System.out.println("current hour:"+currentHour+" hourly avarage response time: "+hourlyResponseTime);
				  partialSum=0;
				  counter=0;
				  currentHour++;
			   }
			   
			   
			}
			
			
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}

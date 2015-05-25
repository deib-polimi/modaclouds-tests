package it.polimi.modaclouds.monitoring.sdaValidator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Scanner;

import util.Workload;

public class WorkloadCSVBuilder {

	public static void main(String[] args) {

		List<Workload> monitored= new ArrayList<Workload>();
		List<Workload> first= new ArrayList<Workload>();;
		List<Workload> second= new ArrayList<Workload>();;
		List<Workload> third= new ArrayList<Workload>();;
		List<Workload> fourth= new ArrayList<Workload>();;
		List<Workload> fifth= new ArrayList<Workload>();;

		
		
		Scanner input;
		String[] splitted;


		for(int i=0 ;i<=5;i++){
			int cont=1;
			BufferedWriter writer = null;
		    
			
			if(i==0){
				try {
					input = new Scanner(new File("monitored_workload.out"));

		           	File tempFile = new File("monitored_workload.csv");
		           	writer = new BufferedWriter(new FileWriter(tempFile));	
		           	writer.write("TIMESTEP,METRIC\n");
		           	
		           	int j=0;
		           	
		           	int[] buffer= new int[30];
					while(input.hasNextLine())
					{
					   String line = input.nextLine();
					   splitted=line.split(",");
					   int value=0;;
					   
					   value=Integer.parseInt(splitted[3]);
					  
					   
					   if(j==29){
						   buffer[j]=value;
						   
						   System.out.println("workload at timestep "+cont);
						   int sum=0;
						   for(int temp: buffer){
							   System.out.println(temp);
							   sum=sum+temp;
						   }
						   System.out.println("-------------------");
						   System.out.println("AVG="+sum/30);
						   writer.write(cont+","+sum/30+"\n");
						   monitored.add(new Workload(cont,sum/30));
						   cont++;
						   j=0;
					   }
					   else{
						   buffer[j]=value;
						   j++;
						   
					   }
					}
		        } catch (Exception e) {
		            e.printStackTrace();
		        } finally {
		            try {
		                writer.close();
		            } catch (Exception e) {
		            }
		        }
			}
			else{
		       try {
					input = new Scanner(new File("forecasted_WL_"+i+".out"));

		           	File tempFile = new File("workload_timestep_"+i+".csv");
		           	writer = new BufferedWriter(new FileWriter(tempFile));	
		           	writer.write("TIMESTEP,METRIC\n");
					while(input.hasNextLine())
					{
					   String line = input.nextLine();
					   splitted=line.split(",");
					   float value=0;;
					   
					  
					   value=Float.parseFloat(splitted[4]);
					   
					   
					   writer.write(cont+","+value+"\n");
					   
					   switch(i){
					   case 1: first.add(new Workload(cont, value));
					   case 2: second.add(new Workload(cont, value));
					   case 3: third.add(new Workload(cont, value));
					   case 4: fourth.add(new Workload(cont, value));
					   case 5: fifth.add(new Workload(cont, value));
					   }
					   cont++;
					}
		        } catch (Exception e) {
		            e.printStackTrace();
		        } finally {
		            try {
		                writer.close();
		            } catch (Exception e) {
		            }
		        }
			}
		}
		
		
		WorkloadGapCalculator.calculate(monitored, first, second, third, fourth, fifth);
	}

}

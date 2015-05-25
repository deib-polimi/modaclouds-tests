package it.polimi.modaclouds.monitoring.sdaValidator;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class DemandValidator {

	private static List<Float> cpu = new ArrayList<Float>();

	private static List<Float> saveRT = new ArrayList<Float>();
	private static List<Float> regRT = new ArrayList<Float>();
	private static List<Float> answRT = new ArrayList<Float>();

	private static Float saveDem;
	private static Float regDem;
	private static Float answDem;

	public static void main(String[] args) {
		perform(Paths.get("."));
	}

	public static final String SDA = "sda.out";
	public static final String RESULT = "demandAnalysis.csv";

	public static void perform(Path parent) {
		Path sda = Paths.get(parent.toString(), SDA);
		if (sda == null || !sda.toFile().exists())
			throw new RuntimeException("SDA file not found or wrong path ("
					+ sda.toString() + ")");

		boolean startCollectSDAResult = false;
		boolean stop = false;
		PrintWriter writer = null;

		Scanner input;
		try {
			input = new Scanner(sda);
			writer = new PrintWriter(Paths.get(parent.toString(), RESULT)
					.toFile(), "UTF-8");

			writer.write("AvarageCPUUtil, "
					+ "AvarageEstimatedDemand_save, AvarageRealResponseTime_save, AvarageEstimatedResponseTime_save, GAP_save,"
					+ "AvarageEstimatedDemand_reg, AvarageRealResponseTime_reg, AvarageEstimatedResponseTime_reg, GAP_reg,"
					+ "AvarageEstimatedDemand_answ, AvarageRealResponseTime_answ, AvarageEstimatedResponseTime_answ, GAP_answ\n");

			String[] splitted;

			while (input.hasNextLine()) {
				String line = input.nextLine();

				if (!startCollectSDAResult && line.contains("FrontendCPUUtilization")) {
					startCollectSDAResult = true;
				}

//				if (line.contains("ENDEND")) {
//					stop = true;
//				}

				if (startCollectSDAResult & !stop) {

					if (checkIntervalCompleted()) {

						writer.write(validate() + "\n");
						flushLists();

					} else {

						if (line.contains("estimationci")) {
							splitted = line.split(" ");
							String datum = splitted[splitted.length - 1]
									.substring(1, splitted[splitted.length - 1]
											.length() - 1);
							Float toAdd = Float.parseFloat(datum.split(":")[1]);

							if (datum.split(":")[0]
									.equals("mic-frontend-mon-saveAnswers")) {
								saveDem = toAdd;
							} else if (datum.split(":")[0]
									.equals("mic-frontend-mon-register")) {
								regDem = toAdd;
							} else if (datum.split(":")[0]
									.equals("mic-frontend-mon-answerQuestions")) {
								answDem = toAdd;
							}
						}

						if (line.contains("AvarageEffectiveResponseTime")) {
							splitted = line.split(" ");
							Float toAdd = Float.parseFloat(splitted[2]
									.split("e")[0]);

							if (splitted[0]
									.equals("mic-frontend-mon-saveAnswers")) {
								saveRT.add(toAdd);
							} else if (splitted[0]
									.equals("mic-frontend-mon-register")) {
								regRT.add(toAdd);

							} else if (splitted[0]
									.equals("mic-frontend-mon-answerQuestions")) {
								answRT.add(toAdd);

							}

						}

						if (line.contains("FrontendCPUUtilization")) {
							splitted = line.split(" ");
							Float toAdd = Float.parseFloat(splitted[2]
									.split("e")[0]);
							if (!Float.isNaN(toAdd.floatValue()))
								cpu.add(toAdd);

						}
					}
				}

			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			writer.flush();
			writer.close();

		}

	}

	private static String validate() {

		// System.out.println("VALIDATION");

		float temp = 0;
		float U;

		float[] saveResult;
		float[] regResult;
		float[] answResult;

		for (Float f : cpu) {
			temp = temp + f.floatValue();
		}

		/*
		 * if(Float.isNaN(temp)){ for(Float f: cpu){
		 * if(Float.isNaN(f.floatValue())){ System.out.println(f.floatValue());
		 * } } System.out.println("####################"); }
		 */
		U = temp / cpu.size();

		saveResult = validateSave(U);
		regResult = validateRegister(U);
		answResult = validateAnsw(U);

		return U + "," + saveResult[0] + "," + saveResult[1] + ","
				+ saveResult[2] + "," + saveResult[3] + "," + regResult[0]
				+ "," + regResult[1] + "," + regResult[2] + "," + regResult[3]
				+ "," + answResult[0] + "," + answResult[1] + ","
				+ answResult[2] + "," + answResult[3];

	}

	private static float[] validateRegister(float cpuUtil) {

		float[] toReturn = new float[4];

		float temp = 0;
		float RTreg;
		float RTregEstim;
		float GAP;

		for (Float f : regRT) {
			temp = temp + f.floatValue();
		}

		RTreg = temp / regRT.size();

		RTregEstim = regDem / (1 - cpuUtil);

		GAP = Math.abs((RTregEstim - RTreg) / RTreg) * 100;

		temp = 0;

		toReturn[0] = regDem;
		toReturn[1] = RTreg;
		toReturn[2] = RTregEstim;
		toReturn[3] = GAP;

		return toReturn;

	}

	private static float[] validateSave(float cpuUtil) {

		float[] toReturn = new float[4];

		float temp = 0;
		float RTsave;
		float RTsaveEstim;
		float GAP;

		for (Float f : saveRT) {
			temp = temp + f.floatValue();
		}

		RTsave = temp / saveRT.size();

		RTsaveEstim = saveDem / (1 - cpuUtil);

		GAP = Math.abs((RTsaveEstim - RTsave) / RTsave) * 100;

		temp = 0;

		toReturn[0] = saveDem;
		toReturn[1] = RTsave;
		toReturn[2] = RTsaveEstim;
		toReturn[3] = GAP;

		return toReturn;

	}

	private static float[] validateAnsw(float cpuUtil) {

		float[] toReturn = new float[4];

		float temp = 0;
		float RTansw;
		float RTanswEstim;
		float GAP;

		for (Float f : answRT) {
			temp = temp + f.floatValue();
		}

		RTansw = temp / answRT.size();

		RTanswEstim = answDem / (1 - cpuUtil);

		GAP = Math.abs((RTanswEstim - RTansw) / RTansw) * 100;

		temp = 0;

		toReturn[0] = answDem;
		toReturn[1] = RTansw;
		toReturn[2] = RTanswEstim;
		toReturn[3] = GAP;

		return toReturn;
	}

	private static void flushLists() {

		cpu.removeAll(cpu);

		saveRT.removeAll(saveRT);

		regRT.removeAll(regRT);

		answRT.removeAll(answRT);

		saveDem = null;
		answDem = null;
		regDem = null;

	}

	private static boolean checkIntervalCompleted() {

		if (saveDem == null | answDem == null | regDem == null)
			return false;
		else
			return true;

	}
}

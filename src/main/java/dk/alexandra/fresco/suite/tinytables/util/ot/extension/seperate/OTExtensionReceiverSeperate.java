/*******************************************************************************
 * Copyright (c) 2016 FRESCO (http://github.com/aicis/fresco).
 *
 * This file is part of the FRESCO project.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * FRESCO uses SCAPI - http://crypto.biu.ac.il/SCAPI, Crypto++, Miracl, NTL,
 * and Bouncy Castle. Please see these projects for any further licensing issues.
 *******************************************************************************/
package dk.alexandra.fresco.suite.tinytables.util.ot.extension.seperate;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import dk.alexandra.fresco.suite.tinytables.util.Encoding;
import dk.alexandra.fresco.suite.tinytables.util.ot.OTReceiver;
import dk.alexandra.fresco.suite.tinytables.util.ot.datatypes.OTSigma;
import dk.alexandra.fresco.suite.tinytables.util.ot.extension.OTExtensionConfig;
import edu.biu.scapi.comm.Party;

/**
 * We use SCAPI's OT Extension library for doing oblivious transfers. However,
 * this lib is implemented in C++ and we need to call it using JNI. Also, for
 * testing we run the different players on the same machine in two different
 * threads which seem to cause problems with the lib, so to avoid that we run
 * the JNI in seperate processes.
 * 
 * @author jonas
 *
 */
public class OTExtensionReceiverSeperate implements OTReceiver {

	private Party party;

	/**
	 * Create a new OTExtensionReceiverSeperate which will perform OTExtension
	 * with a sender running at the address and port as specified in the
	 * <code>party</code> parameter.
	 * 
	 * @param party
	 */
	public OTExtensionReceiverSeperate(Party party) {
		this.party = party;
	}

	@Override
	public List<Boolean> receive(List<OTSigma> sigmas) {
		return transfer(sigmas);
	}

	private List<Boolean> transfer(List<OTSigma> sigmas) {

		try {

			/*
			 * There is an upper bound on how big a terminal cmd can be, so if
			 * we have too many OT's we need to do them a batch at a time.
			 */
			if (sigmas.size() > OTExtensionConfig.MAX_OTS) {
				List<Boolean> out1 = transfer(sigmas.subList(0, OTExtensionConfig.MAX_OTS));
				List<Boolean> out2 = transfer(sigmas.subList(OTExtensionConfig.MAX_OTS, sigmas.size()));

				List<Boolean> out = new ArrayList<Boolean>();
				out.addAll(out1);
				out.addAll(out2);
				return out;
			} else {
				byte[] binarySigmas = Encoding.encodeBooleans(OTSigma.getAll(sigmas));
				byte[] binaryOutput = null;
				/*
				 * We run the OT in a seperate process. Here we call the main
				 * method of NativeOTReceiver. This is required when running the
				 * JUnit tests where the players are on seperate threads in the
				 * same JVM.
				 */
				String base64sigmas = Base64.getEncoder().encodeToString(binarySigmas);

				ProcessBuilder builder = new ProcessBuilder(OTExtensionConfig.SCAPI_CMD, "-cp",
						OTExtensionConfig.CLASSPATH, OTExtensionConfig.OT_RECEIVER,
						party.getIpAddress().getHostName(), Integer.toString(party.getPort()), base64sigmas);
				builder.directory(new File(OTExtensionConfig.PATH));

				Process p = builder.start();

				p.waitFor();

				String base64output = new BufferedReader(new InputStreamReader(p.getInputStream()))
						.lines().collect(Collectors.joining("\n"));

				binaryOutput = Base64.getDecoder().decode(base64output);

				// Decode output to booleans
				List<Boolean> output = Encoding.decodeBooleans(binaryOutput);
				return output;
			}

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}

}

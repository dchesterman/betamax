/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package co.freeside.betamax.proxy;

import static co.freeside.betamax.Headers.VIA_HEADER;
import static co.freeside.betamax.Headers.X_BETAMAX;
import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpHeaders.Names.VIA;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.logging.Level.SEVERE;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.io.FilenameUtils;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.impl.ProxyUtils;

import co.freeside.betamax.encoding.DeflateEncoder;
import co.freeside.betamax.encoding.GzipEncoder;
import co.freeside.betamax.encoding.NoOpEncoder;
import co.freeside.betamax.handler.NonWritableTapeException;
import co.freeside.betamax.message.Response;
import co.freeside.betamax.proxy.netty.NettyRequestAdapter;
import co.freeside.betamax.proxy.netty.NettyResponseAdapter;
import co.freeside.betamax.tape.Tape;

import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.io.ByteStreams;

public class BetamaxFilters extends HttpFiltersAdapter {

	private final String URL_SERVE_LOCAL = "gwt-desktop";
	private final String PATH_TO_SERVE_LOCAL = "/Users/james.park/Documents/Workday/trunk/gwt-shared/target/gwt-gen";

	private NettyRequestAdapter request;
	private NettyResponseAdapter upstreamResponse;
	private final Tape tape;

	private static final Logger LOG = Logger.getLogger(BetamaxFilters.class.getName());

	public BetamaxFilters(HttpRequest originalRequest, Tape tape) {
		super(originalRequest);
		request = NettyRequestAdapter.wrap(originalRequest);
		this.tape = tape;	
	}

	@Override
	public HttpResponse requestPre(HttpObject httpObject) {
		try {
			HttpResponse response = null;
			if (httpObject instanceof HttpRequest) {
				//TODO: I believe this is where the CONNECT needs to be caught...
				// This would require changing the predicate to include all things
				// As well, an appropriate response that the connect succeeded would have to be returned
				// But only if the server we are trying to connect to actually has an entry in the tape
				// It's something of a race condition with the SSL stuff. Because I don't believe we get a path
				// When we have the connect go through. Could we send the connect later if we didn't send it now?
				request.copyHeaders((HttpMessage) httpObject);
			}

			// If we're getting content stick it in there.
			if (httpObject instanceof HttpContent) {
				request.append((HttpContent) httpObject);
			}

			// If it's the last one, we want to take further steps, like checking to see if we've recorded on it!
			if (ProxyUtils.isLastChunk(httpObject)) {
				// We will have collected the last of the http Request finally
				// And now we're ready to intercept it and do proxy-type-things
				response = (isServedLocally()) ?
						onStaticContentRequestIntercepted().orNull() : onRequestIntercepted().orNull();
			}

			return response;
		} catch (IOException e) {
			return createErrorResponse(e);
		}
	}

	@Override
	public HttpResponse requestPost(HttpObject httpObject) {
		if (httpObject instanceof HttpRequest) {
			setViaHeader((HttpMessage) httpObject);
		}

		if(httpObject instanceof HttpResponse) {
			return (HttpResponse) httpObject;
		} else {
			return null;
		}
	}

	@Override
	public HttpObject responsePre(HttpObject httpObject) {

		if (httpObject instanceof HttpResponse) {
			upstreamResponse = NettyResponseAdapter.wrap(httpObject);
		}

		if (httpObject instanceof HttpContent) {
			try {
				upstreamResponse.append((HttpContent) httpObject);
			} catch (IOException e) {
				// TODO: handle in some way
				LOG.log(SEVERE, "Error appending content", e);
			}
		}

		if (ProxyUtils.isLastChunk(httpObject)) {
			if (!isServedLocally() && tape.isWritable()) {
				LOG.info(String.format("Recording to tape %s", tape.getName()));
				tape.record(request, upstreamResponse);
			} else {
				throw new NonWritableTapeException();
			}
		}

		return httpObject;
	}

	@Override
	public HttpObject responsePost(HttpObject httpObject) {
		if (httpObject instanceof HttpResponse) {
			setBetamaxHeader((HttpResponse) httpObject, "REC");
			setViaHeader((HttpMessage) httpObject);
		}

		return httpObject;
	}

	private Optional<? extends FullHttpResponse> onRequestIntercepted() throws IOException {
		if (tape == null) {
			return Optional.of(new DefaultFullHttpResponse(HTTP_1_1, new HttpResponseStatus(403, "No tape")));
		} else if (tape.isReadable() && tape.seek(request)) {
			LOG.warning(String.format("Playing back " + request.getUri() + " from tape %s", tape.getName()));
			Response recordedResponse = tape.play(request);
			FullHttpResponse response = playRecordedResponse(recordedResponse);
			setViaHeader(response);
			setBetamaxHeader(response, "PLAY");
			return Optional.of(response);
		} else {	
			LOG.warning(String.format("no matching request found on %s", tape.getName()));
			return Optional.absent();
		}
	}

	private DefaultFullHttpResponse playRecordedResponse(Response recordedResponse) throws IOException {
		DefaultFullHttpResponse response;
		HttpResponseStatus status = HttpResponseStatus.valueOf(recordedResponse.getStatus());
		if (recordedResponse.hasBody()) {
			ByteBuf content = getEncodedContent(recordedResponse);
			response = new DefaultFullHttpResponse(HTTP_1_1, status, content);
		} else {
			response = new DefaultFullHttpResponse(HTTP_1_1, status);
		}
		for (Map.Entry<String, String> header : recordedResponse.getHeaders().entrySet()) {
			response.headers().set(header.getKey(), Splitter.onPattern(",\\s*").split(header.getValue()));
		}
		return response;
	}

	private ByteBuf getEncodedContent(Response recordedResponse) throws IOException {
		byte[] stream;
		String encodingHeader = recordedResponse.getEncoding();
		if ("gzip".equals(encodingHeader)) {
			stream = new GzipEncoder().encode(ByteStreams.toByteArray(recordedResponse.getBodyAsBinary()));
		} else if ("deflate".equals(encodingHeader)) {
			stream = new DeflateEncoder().encode(ByteStreams.toByteArray(recordedResponse.getBodyAsBinary()));
		} else {
			stream = ByteStreams.toByteArray(recordedResponse.getBodyAsBinary());
		}
		return wrappedBuffer(stream);
	}

	private HttpHeaders setViaHeader(HttpMessage httpMessage) {
		return httpMessage.headers().set(VIA, VIA_HEADER);
	}

	private HttpHeaders setBetamaxHeader(HttpResponse response, String value) {
		return response.headers().add(X_BETAMAX, value);
	}

	private HttpResponse createErrorResponse(Throwable e) {
		// TODO: more detail
		return new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
	}

	// Static content methods

	private boolean isServedLocally() {
		String reqpath = FilenameUtils.getPath(request.getUri().toString());
		if (reqpath.contains(URL_SERVE_LOCAL)) {
			System.out.println("Requested from gwt-desktop/. Serving from local.");
		}
		return reqpath.contains(URL_SERVE_LOCAL);
	}

	private Optional<? extends FullHttpResponse> onStaticContentRequestIntercepted() throws IOException {
		String localpre = PATH_TO_SERVE_LOCAL;
		String staticpath = getRelativeStaticResourcePath();
		File f = new File(localpre + staticpath);
		if (f.exists()) {
			System.out.println("Retrieving static resource: " + (f.toString()) + "...");
			FullHttpResponse response = getStaticContentResponse(f);
			setViaHeader(response);
			return Optional.of(response);
		} else {
			LOG.warning(String.format("no file found for %s", f.getAbsoluteFile())); // file not found
			return Optional.absent(); // retrieve fresh response from target
		}
	}

	private FullHttpResponse getStaticContentResponse(File f) throws IOException {
		byte [] fbytes = ByteStreams.toByteArray(new FileInputStream(f.getAbsoluteFile()));
		byte [] fstream = (request.getHeader("Accept-Encoding").contains("gzip")) ?
				new GzipEncoder().encode(fbytes) : new NoOpEncoder().encode(fbytes);
		ByteBuf content = wrappedBuffer(fstream);
		HttpResponseStatus status = HttpResponseStatus.valueOf(200);
		FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, content);
		
		response.headers().set("Content-Type", getMimeType(f));
		if (request.getHeader("Accept-Encoding").contains("gzip")) {
			response.headers().set("Content-Encoding", "gzip");
		}
		return response;
	}

	// This will have to be improved. Fast and patchy.
	private static String getMimeType(File f) {
		String type = "";
		String ext = FilenameUtils.getExtension(f.getName());
		switch (ext) {
			case "js" : type = "application/javascript"; break;
			case "css" : type = "text/css"; break;
			case "png" : type = "image/png"; break;
			case "gif" : type = "image/gif"; break;
			case "html"	: type = "text/html"; break;
			default: type = "text/plain"; break;
		}
		return type;
	}

	private String getRelativeStaticResourcePath() {
		String reqpath = request.getUri().toString();
		return reqpath.substring(reqpath.lastIndexOf(URL_SERVE_LOCAL) + URL_SERVE_LOCAL.length());
	}
}
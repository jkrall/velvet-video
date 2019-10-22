package com.zakgof.velvetvideo.impl;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.sound.sampled.AudioFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zakgof.velvetvideo.Direction;
import com.zakgof.velvetvideo.IAudioEncoderBuilder;
import com.zakgof.velvetvideo.IAudioFrame;
import com.zakgof.velvetvideo.IAudioStreamProperties;
import com.zakgof.velvetvideo.IDecodedPacket;
import com.zakgof.velvetvideo.IDecoderAudioStream;
import com.zakgof.velvetvideo.IDecoderStream;
import com.zakgof.velvetvideo.IDecoderVideoStream;
import com.zakgof.velvetvideo.IDemuxer;
import com.zakgof.velvetvideo.IEncoderAudioStream;
import com.zakgof.velvetvideo.IEncoderVideoStream;
import com.zakgof.velvetvideo.IMuxer;
import com.zakgof.velvetvideo.IMuxerBuilder;
import com.zakgof.velvetvideo.IMuxerProperties;
import com.zakgof.velvetvideo.IRemuxerBuilder;
import com.zakgof.velvetvideo.IRemuxerStream;
import com.zakgof.velvetvideo.ISeekableInput;
import com.zakgof.velvetvideo.ISeekableOutput;
import com.zakgof.velvetvideo.IVelvetVideoLib;
import com.zakgof.velvetvideo.IVideoEncoderBuilder;
import com.zakgof.velvetvideo.IVideoFrame;
import com.zakgof.velvetvideo.IVideoStreamProperties;
import com.zakgof.velvetvideo.MediaType;
import com.zakgof.velvetvideo.VelvetVideoException;
import com.zakgof.velvetvideo.impl.jnr.AVCodec;
import com.zakgof.velvetvideo.impl.jnr.AVCodecContext;
import com.zakgof.velvetvideo.impl.jnr.AVDictionaryEntry;
import com.zakgof.velvetvideo.impl.jnr.AVFormatContext;
import com.zakgof.velvetvideo.impl.jnr.AVFrame;
import com.zakgof.velvetvideo.impl.jnr.AVIOContext;
import com.zakgof.velvetvideo.impl.jnr.AVOutputFormat;
import com.zakgof.velvetvideo.impl.jnr.AVPacket;
import com.zakgof.velvetvideo.impl.jnr.AVPixelFormat;
import com.zakgof.velvetvideo.impl.jnr.AVSampleFormat;
import com.zakgof.velvetvideo.impl.jnr.AVStream;
import com.zakgof.velvetvideo.impl.jnr.LibAVCodec;
import com.zakgof.velvetvideo.impl.jnr.LibAVFormat;
import com.zakgof.velvetvideo.impl.jnr.LibAVFormat.ICustomAvioCallback;
import com.zakgof.velvetvideo.impl.jnr.LibAVUtil;
import com.zakgof.velvetvideo.impl.middle.AudioFrameHolder;
import com.zakgof.velvetvideo.impl.middle.BestMatchingAudioFormatConvertor;
import com.zakgof.velvetvideo.impl.middle.Feeder;
import com.zakgof.velvetvideo.impl.middle.Filters;
import com.zakgof.velvetvideo.impl.middle.IFrameHolder;
import com.zakgof.velvetvideo.impl.middle.VideoFrameHolder;

import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import jnr.ffi.Struct;
import jnr.ffi.byref.PointerByReference;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.Value;
import lombok.experimental.Accessors;

public class VelvetVideoLib implements IVelvetVideoLib {

    private static final int AVIO_CUSTOM_BUFFER_SIZE = 32768;

    private static final int CODEC_FLAG_GLOBAL_HEADER  = 1 << 22;

    private static final int  AVSEEK_FLAG_BACKWARD =1; ///< seek backward
    private static final int  AVSEEK_FLAG_BYTE     =2; ///< seeking based on position in bytes
    private static final int  AVSEEK_FLAG_ANY      =4; ///< seek to any frame, even non-keyframes
    private static final int  AVSEEK_FLAG_FRAME    =8;

    public static final int AVERROR_EOF = -541478725;
    public static final int AVERROR_EAGAIN = -11;
    private static final long AVNOPTS_VALUE = LibAVUtil.AVNOPTS_VALUE;

    // private static final Logger ffmpegLogger = LoggerFactory.getLogger("velvet-video.ffmpeg");

    private final LibAVUtil libavutil = JNRHelper.load(LibAVUtil.class, "avutil-56");
    private final LibAVCodec libavcodec = JNRHelper.load(LibAVCodec.class, "avcodec-58");
    private final LibAVFormat libavformat = JNRHelper.load(LibAVFormat.class, "avformat-58");

    private int checkcode(int code) {
    	return libavutil.checkcode(code);
    }

    @Override
    public List<String> codecs(Direction dir, MediaType mediaType) {
    	return libavcodec.codecs(dir, mediaType);
    }

    @Override
    public IVideoEncoderBuilder videoEncoder(String codec) {
        return new VideoEncoderBuilderImpl(codec);
    }

    @Override
    public IAudioEncoderBuilder audioEncoder(String codec, AudioFormat audioFormat) {
    	return new AudioEncoderBuilderImpl(codec, audioFormat);
    }

    @Override
    public IRemuxerBuilder remuxer(IDecoderStream<?, ?, ?> decoder) {
    	return new RemuxerBuilderImpl(decoder);
    }

    private String defaultName(AVStream avstream, int index) {
        AVDictionaryEntry entry = libavutil.av_dict_get(avstream.metadata.get(), "handler_name", null, 0);
        if (entry != null) {
            String name = entry.value.get();
            if (!name.equals("VideoHandler")) {
                return name;
            }
        }
        return "video" + index;
    }

    private void initCustomAvio(boolean read, AVFormatContext formatCtx, ICustomAvioCallback callback) {
        Pointer buffer = libavutil.av_malloc(AVIO_CUSTOM_BUFFER_SIZE + 64); // TODO free buffer
        AVIOContext avioCtx = libavformat.avio_alloc_context(buffer, AVIO_CUSTOM_BUFFER_SIZE, read ? 0 : 1, null, read ? callback : null, read ? null : callback, callback);
        int flagz = formatCtx.ctx_flags.get();
        formatCtx.ctx_flags.set(LibAVFormat.AVFMT_FLAG_CUSTOM_IO | flagz);
        formatCtx.pb.set(avioCtx); // TODO free avioCtx and buffer
    }

    private AVFormatContext createMuxerFormatContext(String format, Map<String, String> metadata) {
        AVOutputFormat outputFmt = libavformat.av_guess_format(format, null, null);
        if (outputFmt == null) {
            throw new VelvetVideoException("Unsupported format: " + format);
        }
        PointerByReference ctxptr = new PointerByReference();
        checkcode(libavformat.avformat_alloc_output_context2(ctxptr, outputFmt, null, null));
        AVFormatContext ctx = JNRHelper.struct(AVFormatContext.class, ctxptr.getValue());
        Pointer[] metadataPtr = libavutil.createDictionary(metadata);
        ctx.metadata.set(metadataPtr[0]);
        return ctx;
    }

    private abstract class AbstractMuxerStreamImpl implements AutoCloseable {
    	protected final Logger logEncoder = LoggerFactory.getLogger("velvet-video.encoder");
		final Consumer<AVPacket> output;
		final AVPacket packet;
	    AVStream stream;
		int codecTimeBaseNum;
		int codecTimeBaseDen;
		int streamIndex;
		long nextPts = 0;
		int defaultFrameDuration;

		AbstractMuxerStreamImpl(Consumer<AVPacket> output) {
			this.output = output;
			this.packet = libavcodec.av_packet_alloc();
		}

		public void init() {
			this.defaultFrameDuration = codecTimeBaseNum * stream.time_base.den.get() / codecTimeBaseDen / stream.time_base.num.get();
			this.streamIndex = stream.index.get();
			logEncoder.atInfo()
				.addArgument(stream.index.get())
	        	.addArgument(stream.time_base.num.get())
	        	.addArgument(stream.time_base.den.get())
	        	.addArgument(stream.codec.get().codec.get().name.get())
	        	.addArgument(codecTimeBaseNum)
	        	.addArgument(codecTimeBaseDen)
	        	.log("stream {}: timebase: {}/{}, codec [{}] timebase {}/{}");

		}

		@Override
		public void close() {
			libavcodec.av_packet_free(new Pointer[] {Struct.getMemory(packet)});
		}

	}

    private class RemuxerStreamImpl extends AbstractMuxerStreamImpl implements IRemuxerStream {

    	public RemuxerStreamImpl(RemuxerBuilderImpl builder, AVFormatContext formatCtx, Consumer<AVPacket> output) {
			super(output);
    		this.stream = libavformat.avformat_new_stream(formatCtx, null);
			DemuxerImpl.DecoderVideoStreamImpl decoderImpl = (DemuxerImpl.DecoderVideoStreamImpl) builder.decoder;
			checkcode(libavcodec.avcodec_parameters_copy(stream.codecpar.get(), decoderImpl.avstream.codecpar.get()));
        	stream.codecpar.get().codec_tag.set(0);
        	int timeBaseNum = builder.timebaseNum == null ? decoderImpl.codecCtx.time_base.num.get() * decoderImpl.codecCtx.ticks_per_frame.get(): builder.timebaseNum;
        	int timeBaseDen = builder.timebaseDen == null ? decoderImpl.codecCtx.time_base.den.get() : builder.timebaseDen;
        	stream.time_base.num.set(timeBaseNum);
            stream.time_base.den.set(timeBaseDen);
            this.codecTimeBaseNum = timeBaseNum;
            this.codecTimeBaseDen = timeBaseDen;
		}

    	@Override
		public void writeRaw(byte[] packetData) {
			// TODO !!! free
			Pointer pointer = Runtime.getSystemRuntime().getMemoryManager().allocateDirect(packetData.length);
			pointer.put(0, packetData, 0, packetData.length);
            libavcodec.av_init_packet(packet);
            packet.data.set(pointer);
            packet.size.set(packetData.length);
            packet.stream_index.set(streamIndex);
            packet.pts.set(nextPts);
			packet.duration.set(defaultFrameDuration);
			nextPts += defaultFrameDuration;
            output.accept(packet);
		}

    }

    private abstract class AbstractEncoderStreamImpl<B extends AbstractEncoderBuilderImpl<?>> extends AbstractMuxerStreamImpl {

        protected final AVCodecContext codecCtx;

        protected final AVCodec codec;
        protected final Pointer[] codecOpts;

		protected final String filterString;
		protected Filters filters;
		private Map<Long, Integer> frameDurationCache = new HashMap<>();

        public AbstractEncoderStreamImpl(B builder, AVFormatContext formatCtx, Consumer<AVPacket> output) {
        	super(output);
			this.codecOpts = libavutil.createDictionary(builder.params);
			this.filterString = builder.filter;
            this.codec = libavcodec.avcodec_find_encoder_by_name(builder.codec);
            if (this.codec == null && builder.decoder == null) {
                throw new VelvetVideoException("Unknown video codec: " + builder.codec);
            }
            this.stream = libavformat.avformat_new_stream(formatCtx, codec);

            this.codecCtx = libavcodec.avcodec_alloc_context3(codec);
            if ((formatCtx.ctx_flags.get() & LibAVFormat.AVFMT_GLOBALHEADER) != 0) {
            	codecCtx.flags.set(codecCtx.flags.get() | CODEC_FLAG_GLOBAL_HEADER);
            }
            codecCtx.codec_id.set(codec.id.get());
            codecCtx.codec_type.set(codec.type.get());
            codecCtx.bit_rate.set(builder.bitrate == null ? 400000 : builder.bitrate);
            codecCtx.time_base.num.set(builder.timebaseNum == null ? 1 : builder.timebaseNum);
            codecCtx.time_base.den.set(builder.timebaseDen == null ? 30 : builder.timebaseDen);

            if (builder.enableExperimental) {
            	codecCtx.strict_std_compliance.set(-2);
            }

            initCodecCtx(builder);

            Pointer[] metadata = libavutil.createDictionary(builder.metadata);
            stream.metadata.set(metadata[0]);
            checkcode(libavcodec.avcodec_parameters_from_context(stream.codecpar.get(), codecCtx));

            stream.time_base.num.set(codecCtx.time_base.num.get());
            stream.time_base.den.set(codecCtx.time_base.den.get());
            stream.index.set(formatCtx.nb_streams.get() - 1);
            stream.id.set(formatCtx.nb_streams.get() - 1);

        	this.codecTimeBaseNum = codecCtx.time_base.num.get();
            this.codecTimeBaseDen = codecCtx.time_base.den.get();
        }

		abstract void initCodecCtx(B builder);

		protected void submitFrame(AVFrame frame, int duration) {
			if (frame != null) {
				frameDurationCache.put(frame.pts.get(), duration);
			}
			if (filters == null) {
				encodeFrame(frame);
			} else {
				Feeder.feed(frame,
					inputFrame -> filters.submitFrame(inputFrame), // TODO : lost duration
					outputFrame -> encodeFrame(outputFrame));
			}
		}

        private void encodeFrame(AVFrame frame) {
        	logEncoder.atDebug().log(() -> frame == null ? "stream " + streamIndex + ": flush" :  "stream " + streamIndex + ": send frame for encoding, PTS=" + frame.pts.get() + " duration=" + frameDurationCache.get(frame.pts.get()));
            checkcode(libavcodec.avcodec_send_frame(codecCtx, frame));
            for (;;) {
                libavcodec.av_init_packet(packet);
                packet.data.set((Pointer) null);
                packet.size.set(0);

                int res = libavcodec.avcodec_receive_packet(codecCtx, packet);
                if (res == AVERROR_EAGAIN || res == AVERROR_EOF)
                    break;
                checkcode(res);
                packet.stream_index.set(streamIndex);
        		if ((packet.duration.get() == 0 || packet.duration.get() == AVNOPTS_VALUE)) {
        			Integer dur = frameDurationCache.remove(packet.pts.get());
        			if (dur != null) {
        				packet.duration.set(dur);
        			} else {
        				packet.duration.set(defaultFrameDuration);
        			}
    			}

//                Integer dur = frameDurationCache.remove(packet.pts.get());
//                packet.pts.set(codecToStream(packet.pts.get()));
//                packet.dts.set(codecToStream(packet.dts.get()));
//                if (dur != null)
//                	packet.duration.set(codecToStream(dur));
//                else
//                	packet.duration.set(codecToStream(packet.duration.get()));

                logEncoder.atDebug()
                	.addArgument(() -> packet.pts.get())
                	.addArgument(() -> packet.dts.get())
                	.addArgument(() -> packet.duration.get())
                	.addArgument(() -> packet.size.get())
                	.log(() -> "encoder: returned packet  PTS/DTS: {}/{}, duration={}, {} bytes");

                output.accept(packet);
            }
        }

		private long codecToStream(long dur) {
			return dur * stream.time_base.den.get() * codecCtx.time_base.num.get() / (stream.time_base.num.get() * codecCtx.time_base.den.get());
		}

		@Override
		public void close() {
			submitFrame(null, defaultFrameDuration);
			super.close();
			libavcodec.avcodec_close(codecCtx);
			libavcodec.avcodec_free_context(new Pointer[] { Struct.getMemory(codecCtx) });
		}

		public void setFilter(String filterString) {
			this.filters = new Filters(codecCtx, filterString);
		}

    }

    private class VideoEncoderStreamImpl extends AbstractEncoderStreamImpl<VideoEncoderBuilderImpl> implements IEncoderVideoStream {

		private VideoFrameHolder frameHolder;
		private boolean codecOpened;

		public VideoEncoderStreamImpl(VideoEncoderBuilderImpl builder, AVFormatContext formatCtx,
				Consumer<AVPacket> output) {
			super(builder, formatCtx, output);
		}

		@Override
		void initCodecCtx(VideoEncoderBuilderImpl builder) {
            codecCtx.width.set(builder.width == null ? 1 : builder.width);
            codecCtx.height.set(builder.height == null ? 1 : builder.height);
            int firstFormat = codec.pix_fmts.get().getInt(0);
            codecCtx.pix_fmt.set(firstFormat); // TODO ?
		}

		@Override
        public void encode(BufferedImage image) {
			encode(image, defaultFrameDuration);
		}

		@Override
        public void encode(BufferedImage image, int duration) {
            int width = image.getWidth();
            int height = image.getHeight();

            if (!this.codecOpened) {
            	codecCtx.width.set(width);
                codecCtx.height.set(height);
                checkcode(libavcodec.avcodec_parameters_from_context(stream.codecpar.get(), codecCtx));
                checkcode(libavcodec.avcodec_open2(codecCtx, codecCtx.codec.get(), codecOpts));
                codecOpened = true;
                if (filterString != null)
                	this.filters = new Filters(codecCtx, filterString);
            } else {
            	if (codecCtx.width.get() != width || codecCtx.height.get() != height) {
            		throw new VelvetVideoException("Image dimensions do not match, expected " + codecCtx.width.get() + "x" + codecCtx.height.get());
            	}
            }

            if (frameHolder == null) {
            	frameHolder = new VideoFrameHolder(width, height, AVPixelFormat.avformatOf(image.getType()), codecCtx.pix_fmt.get(), stream.time_base, true);
            }

            AVFrame frame = frameHolder.setPixels(image);
            frame.extended_data.set(frame.data[0].getMemory());
            frame.pts.set(nextPts);
            nextPts += duration;
            submitFrame(frame, duration);
        }

    }

    private class AudioEncoderStreamImpl extends AbstractEncoderStreamImpl<AudioEncoderBuilderImpl> implements IEncoderAudioStream {

 		private AudioFrameHolder frameHolder;
		private AudioFormat inputSampleFormat;
		private AudioFormat codecSampleFormat;

		public AudioEncoderStreamImpl(AudioEncoderBuilderImpl builder, AVFormatContext formatCtx,
 				Consumer<AVPacket> output) {
 			super(builder, formatCtx, output);
 			frameHolder = new AudioFrameHolder(codecCtx.time_base, true, codecCtx, builder.inputFormat);
 		}

		@Override
		void initCodecCtx(AudioEncoderBuilderImpl builder) {

			this.inputSampleFormat = builder.inputFormat;
			Set<AudioFormat> supportedFormats = getSampleFormats(codec.sample_fmts.get());
			AudioFormat codecSampleFormat = new BestMatchingAudioFormatConvertor(supportedFormats).apply(inputSampleFormat);
			AudioFormat codecAudioFormat = AVSampleFormat.from(codecSampleFormat).toAudioFormat((int)inputSampleFormat.getSampleRate(), inputSampleFormat.getChannels());

			codecCtx.channels.set(inputSampleFormat.getChannels());
			codecCtx.channel_layout.set(libavutil.av_get_default_channel_layout(inputSampleFormat.getChannels()));
			codecCtx.sample_rate.set((int)inputSampleFormat.getSampleRate());
			codecCtx.sample_fmt.set(AVSampleFormat.from(codecAudioFormat));
			codecCtx.bit_rate.set(builder.bitrate == null ? 64000 : builder.bitrate); //TODO default audio bit rate ?

			checkcode(libavcodec.avcodec_open2(codecCtx, codecCtx.codec.get(), codecOpts));
		}

		private Set<AudioFormat> getSampleFormats(Pointer sample_fmts) {
			Set<AudioFormat> formats = new HashSet<>();
			for (int offset = 0;;) {
				int val = sample_fmts.getInt(offset);
				if (val == -1)
					return formats;
				formats.add(AVSampleFormat.values()[val].toAudioFormat(-1, 0));
				offset += 4;
			}
		}

		@Override
		public void encode(byte[] samples) {
			encode(samples, 0);
		}

		@Override
		public void encode(byte[] samples, int offset) {
			int duration = frameHolder.put(samples, offset);
			AVFrame frame = frameHolder.frame();
			frame.pts.set(nextPts);
            nextPts += duration;
			submitFrame(frame, duration);
		}

		@Override
		public int frameBytes() {
			return frameHolder.frameBytes();
		}
    }
    @Override
    public IMuxerBuilder muxer(String format) {
        return new MuxerBuilderImpl(format);
    }

    private class MuxerBuilderImpl implements IMuxerBuilder {

    	@RequiredArgsConstructor
    	private class BuilderRec {

    		public BuilderRec(IVideoEncoderBuilder video) {
				this((VideoEncoderBuilderImpl) video, null, null);
			}

    		public BuilderRec(IAudioEncoderBuilder audio) {
				this(null, (AudioEncoderBuilderImpl) audio, null);
			}

			public BuilderRec(IRemuxerBuilder remuxer) {
				this(null, null, (RemuxerBuilderImpl) remuxer);
			}

			private final VideoEncoderBuilderImpl video;
			private final AudioEncoderBuilderImpl audio;
			private final RemuxerBuilderImpl remuxer;
    	}

        private final String format;
        private final List<BuilderRec> builders = new ArrayList<>();

        private final Map<String, String> metadata = new LinkedHashMap<>();

        public MuxerBuilderImpl(String format) {
            this.format = format;
        }

        @Override
        public IMuxerBuilder videoEncoder(IVideoEncoderBuilder encoderBuilder) {
        	builders.add(new BuilderRec(encoderBuilder));
            return this;
        }

        @Override
        public IMuxerBuilder audioEncoder(IAudioEncoderBuilder encoderBuilder) {
        	builders.add(new BuilderRec(encoderBuilder));
            return this;
        }

        @Override
        public IMuxerBuilder remuxer(IDecoderStream<?, ?, ?> decoder) {
			return remuxer(new RemuxerBuilderImpl(decoder));
		}

		@Override
		public IMuxerBuilder remuxer(IRemuxerBuilder remuxerBuilder) {
			builders.add(new BuilderRec(remuxerBuilder));
			return this;
		}

        @Override
        public IMuxerBuilder metadata(String key, String value) {
            metadata.put(key, value);
            return this;
        }

        @Override
        public IMuxer build(ISeekableOutput output) {
            return new MuxerImpl(output, this);
        }

        @Override
        public IMuxer build(File outputFile) {
            try {
                FileSeekableOutput output = new FileSeekableOutput(new FileOutputStream(outputFile));
                return new MuxerImpl(output, this);
            } catch (FileNotFoundException e) {
                throw new VelvetVideoException(e);
            }
        }


    }

    private class MuxerImpl implements IMuxer {

    	private final Logger logMuxer = LoggerFactory.getLogger("velvet-video.muxer");

        private final LibAVFormat libavformat;
        private final List<VideoEncoderStreamImpl> videoStreams = new ArrayList<>();
        private final List<AudioEncoderStreamImpl> audioStreams = new ArrayList<>();
        private final List<RemuxerStreamImpl> remuxerStreams = new ArrayList<>();

        private final ISeekableOutput output;
        private final AVFormatContext formatCtx;
        private final IOCallback callback;

        private MuxerImpl(ISeekableOutput output, MuxerBuilderImpl builder) {

            this.libavformat = JNRHelper.load(LibAVFormat.class, "avformat-58");
            this.output = output;
            this.formatCtx = createMuxerFormatContext(builder.format, builder.metadata);
            this.callback = new IOCallback();
            initCustomAvio(false, formatCtx, callback);

            Consumer<AVPacket> packetStream = packet -> {
            	logMuxer.atDebug()
            		.addArgument(() -> packet.pts.get())
            		.addArgument(() -> packet.dts.get())
            		.addArgument(() -> packet.duration.get())
            		.addArgument(() -> packet.size.get())
            		.log("writing packet PTS/DTS = {}/{}, duration={}, {} bytes");
            	checkcode(libavformat.av_write_frame(formatCtx, packet));
            };

            builder.builders.stream()
            	.forEach(brec ->  {
            		if (brec.video != null) {
            			videoStreams.add(new VideoEncoderStreamImpl(brec.video, formatCtx, packetStream));
            		} else if (brec.audio != null) {
            			audioStreams.add(new AudioEncoderStreamImpl(brec.audio, formatCtx, packetStream));
            		} else if (brec.remuxer != null) {
            			remuxerStreams.add(new RemuxerStreamImpl(brec.remuxer, formatCtx, packetStream));
            		} else {
            			throw new VelvetVideoException("Unknown video stream builder type");
            		}
            	 });

            checkcode(libavformat.avformat_write_header(formatCtx, null));

            // TODO: fix dis hack
            videoStreams.forEach(enc -> {
            	enc.init();
            });
            audioStreams.forEach(enc -> {
            	enc.init();
            });
            remuxerStreams.forEach(enc -> {
            	enc.init();
            });
        }

		@Override
		public IEncoderVideoStream videoEncoder(int index) {
			return videoStreams.stream().filter(vs -> vs.streamIndex == index).findFirst()
					.orElseThrow(() -> new VelvetVideoException("No video stream found with index " + index));
		}

		@Override
		public IEncoderAudioStream audioEncoder(int index) {
			return audioStreams.stream().filter(vs -> vs.streamIndex == index).findFirst()
					.orElseThrow(() -> new VelvetVideoException("No audio stream found with index " + index));
		}

		@Override
		public IRemuxerStream remuxer(int index) {
			return remuxerStreams.stream().filter(vs -> vs.streamIndex == index).findFirst()
					.orElseThrow(() -> new VelvetVideoException("No remuxer stream found with index " + index));
		}

		private class IOCallback implements ICustomAvioCallback {

			@Override
			public int read_packet(Pointer opaque, Pointer buf, int buf_size) {
				// TODO [low] perf: prealloc buffer
				byte[] bytes = new byte[buf_size];
				buf.get(0, bytes, 0, buf_size);
				output.write(bytes);
				return buf_size;
			}

            @Override
            public int seek(Pointer opaque, int offset, int whence) {
                // TODO [low] support other whence values
                if (whence != 0)
                    throw new IllegalArgumentException();
                output.seek(offset);
                return offset;
            }
        }

        @Override
        public void close() {
            // flush encoders
        	// TODO: order ??
            for (VideoEncoderStreamImpl encoder : videoStreams) {
                encoder.close();
            }
            for (AudioEncoderStreamImpl encoder : audioStreams) {
                encoder.close();
            }
            // flush muxer
            do {
            	logMuxer.atDebug().log("flushing");
            } while (checkcode(libavformat.av_write_frame(formatCtx, null)) == 0);

            logMuxer.atDebug().log("writing trailer");
            checkcode(libavformat.av_write_trailer(formatCtx));
            // dispose resources
            // libavformat.avio_context_free(new PointerByReference(Struct.getMemory(avioCtx)));
            libavformat.avformat_free_context(formatCtx);
            output.close();
        }


    }

    @Override
    public IDemuxer demuxer(InputStream is) {
        return new DemuxerImpl((FileInputStream) is);
    }

    public class DemuxerImpl implements IDemuxer {

    	private final Logger logDemuxer = LoggerFactory.getLogger("velvet-video.demuxer");
        private final AVFormatContext formatCtx;
        private final ISeekableInput input;
        private final IOCallback callback;

        private final AVPacket packet;
        private final Map<Integer, DecoderVideoStreamImpl> indexToVideoStream = new LinkedHashMap<>();
        private final Map<Integer, DecoderAudioStreamImpl> indexToAudioStream = new LinkedHashMap<>();
        private final List<AbstractDecoderStream> allStreams = new ArrayList<>();
		private int flushStreamIndex = 0;

        public DemuxerImpl(FileInputStream input) {
            this.input = new FileSeekableInput(input);

            this.packet = libavcodec.av_packet_alloc(); // TODO free

            formatCtx = libavformat.avformat_alloc_context();
            this.callback = new IOCallback();
            initCustomAvio(true, formatCtx, callback);

            PointerByReference ptrctx = new PointerByReference(Struct.getMemory(formatCtx));
            checkcode(libavformat.avformat_open_input(ptrctx, null, null, null));
            checkcode(libavformat.avformat_find_stream_info(formatCtx, null));

            long nb = formatCtx.nb_streams.get();
            Pointer pointer = formatCtx.streams.get();
            for (int i=0; i<nb; i++) {
                Pointer mem = pointer.getPointer(i * pointer.getRuntime().addressSize());
                AVStream avstream = JNRHelper.struct(AVStream.class, mem);
                int mediaType = avstream.codec.get().codec_type.get();
				if (mediaType == LibAVCodec.AVMEDIA_TYPE_VIDEO) {
                    avstream.codec.get().strict_std_compliance.set(-2);
                    DecoderVideoStreamImpl decoder = new DecoderVideoStreamImpl(avstream, defaultName(avstream, i));
                    indexToVideoStream.put(i, decoder);
                    allStreams.add(decoder);
                } else if (mediaType == LibAVCodec.AVMEDIA_TYPE_AUDIO) { // TODO dry
                    avstream.codec.get().strict_std_compliance.set(-2);
                    DecoderAudioStreamImpl decoder = new DecoderAudioStreamImpl(avstream, defaultName(avstream, i));
                    indexToAudioStream.put(i, decoder);
                    allStreams.add(decoder);
                }
            }
        }

        private class IOCallback implements ICustomAvioCallback {

            @Override
            public int read_packet(Pointer opaque, Pointer buf, int buf_size) {
                byte[] bytes = new byte[buf_size];
                int bts;
                bts = input.read(bytes);
                if (bts > 0) {
                    buf.put(0, bytes, 0, bts);
                }
                return bts;
            }

            @Override
            public int seek(Pointer opaque, int offset, int whence) {

                final int SEEK_SET = 0;   /* set file offset to offset */
                final int SEEK_CUR = 1;   /* set file offset to current plus offset */
                final int SEEK_END = 2;   /* set file offset to EOF plus offset */
                final int AVSEEK_SIZE = 0x10000;   /* set file offset to EOF plus offset */

                if (whence == SEEK_SET)
                    input.seek(offset);
                else if (whence == SEEK_END)
                    input.seek(input.size() - offset);
                else if (whence == AVSEEK_SIZE)
                    return (int) input.size();
                else throw new VelvetVideoException("Unsupported seek operation " + whence);
                return offset;
            }

        }

        @Override
		public IDecodedPacket nextPacket() {
        	return Feeder.next(this::nextRawPacket, this::decodePacket);
        }

        private AVPacket nextRawPacket() {
			libavcodec.av_init_packet(packet);
			packet.data.set((Pointer) null);
			packet.size.set(0);
			int res = libavformat.av_read_frame(formatCtx, packet);
			if (res == AVERROR_EOF || res == -1) {
				logDemuxer.atDebug()
					.log(() -> "muxer empty");
				return null;
			}
			checkcode(res);
			logDemuxer.atDebug()
				.addArgument(packet.stream_index.get())
				.addArgument(packet.pts.get())
				.addArgument(packet.dts.get())
				.addArgument(packet.duration.get())
				.log(() -> "stream {}: read packet PTS/DTS={}/{} duration={}");
			return packet;
        }

        @Override
        public Stream<IDecodedPacket> stream() {
        	// return Stream.generate(this::nextPacket).takeWhile(el -> el != null);
        	return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false);
        }

        @Override
        public Iterator<IDecodedPacket> iterator() {
        	return new Iterator<IDecodedPacket>() {

        		private IDecodedPacket next = nextPacket();

				@Override
				public boolean hasNext() {
					return next != null;
				}

				@Override
				public IDecodedPacket next() {
					if (next == null)
						throw new NoSuchElementException();
					IDecodedPacket ret = next;
					next = nextPacket();
					return ret;
				}
			};
        }

        /**
		 * @return null means "PACKET HAS NO OUTPUT DATA, GET NEXT PACKET"
		 */
		private IDecodedPacket decodePacket(AVPacket p) {
			if (p != null) {
				return decodeRawPacket(p);
			} else {
				return flushNextStream();
			}
		}

		private IDecodedPacket decodeRawPacket(AVPacket p) {
			int index = p.stream_index.get();
			DecoderVideoStreamImpl videoStream = indexToVideoStream.get(index);
			if (videoStream != null)
				return videoStream.decodePacket(p);
			DecoderAudioStreamImpl audioStream = indexToAudioStream.get(index);
			if (audioStream != null)
				return audioStream.decodePacket(p);
			logDemuxer.atWarn().addArgument(index).log("received packet of unknown stream {}");
			return new UnknownDecodedPacket(packet.bytes());
		}

		private IDecodedPacket flushNextStream() {
			for (; flushStreamIndex < allStreams.size(); flushStreamIndex++) {
				logDemuxer.atDebug().addArgument(flushStreamIndex).log(() -> "flushing demuxer stream={}");
				AbstractDecoderStream stream = allStreams.get(flushStreamIndex);
				IDecodedPacket packet = stream.decodePacket(null);
				if (packet != null) {
					return packet;
				}
			}
			return null;
		}

		private class DecoderVideoStreamImpl extends AbstractDecoderStream implements IDecoderVideoStream {

			public DecoderVideoStreamImpl(AVStream avstream, String name) {
				super(avstream, name);
			}

			@Override
			public IVideoStreamProperties properties() {
				int timebase_n = avstream.time_base.num.get();
				int timebase_d = avstream.time_base.den.get();
				long duration = avstream.duration.get() * 1000000000L * timebase_n / timebase_d;
				long frames = avstream.nb_frames.get();
				int width = codecCtx.width.get();
				int height = codecCtx.height.get();
				AVCodec codec = libavcodec.avcodec_find_decoder(codecCtx.codec_id.get());
				double framerate = (double) avstream.avg_frame_rate.num.get() / avstream.avg_frame_rate.den.get();
				return new VideoStreamProperties(codec.name.get(), framerate, duration, frames, width, height);
			}

			@Override
			public IVideoFrame nextFrame() {
				IDecodedPacket packet;
				while((packet = nextPacket()) != null) {
					if (packet.isVideo() && packet.video().stream() == this) {
						return packet.video();
					}
				}
				return null;
			}

			@Override
			public IDecoderVideoStream seek(long frameNumber) {
				seekToFrame(frameNumber);
				return this;
			}

			@Override
			public IDecoderVideoStream seekNano(long ns) {
				seekToNano(ns);
				return this;
			}

            @Override
            protected IFrameHolder createFrameHolder() {
            	return new VideoFrameHolder(codecCtx.width.get(), codecCtx.height.get(), codecCtx.pix_fmt.get(), AVPixelFormat.AV_PIX_FMT_BGR24, avstream.time_base, false);
            }

		}

		private class DecoderAudioStreamImpl extends AbstractDecoderStream implements IDecoderAudioStream {

			private AudioFormat targetFormat;

			public DecoderAudioStreamImpl(AVStream avstream, String name) {
				super(avstream, name);
		    	AudioFormat suggestedFormat = codecCtx.sample_fmt.get().destFormat().toAudioFormat(codecCtx.sample_rate.get(), codecCtx.channels.get());
		    	targetFormat = new BestMatchingAudioFormatConvertor().apply(suggestedFormat);
		    	logDecoder.atInfo().addArgument(index()).addArgument(targetFormat).log("stream {}: audio format [{}]");
		    	if (!targetFormat.equals(suggestedFormat)) {
		    		logDecoder.atWarn().addArgument(suggestedFormat).addArgument(targetFormat).log("Audio format converted [{}] -> [{}]");
		    	}
			}

			@Override
			public IAudioFrame nextFrame() {
				IDecodedPacket packet;
				while((packet = nextPacket()) != null) {
					if (packet.isAudio() && packet.audio().stream() == this) {
						return packet.audio();
					}
				}
				return null;
			}

			@Override
			public IAudioStreamProperties properties() {
				// TODO DRY
				int timebase_n = avstream.time_base.num.get();
				int timebase_d = avstream.time_base.den.get();
				long duration = avstream.duration.get() * 1000000000L * timebase_n / timebase_d;
				long frames = avstream.nb_frames.get();
				AVCodec codec = libavcodec.avcodec_find_decoder(codecCtx.codec_id.get());
				return new AudioStreamPropertiesImpl(codec.name.get(), targetFormat, duration, frames);
			}

			@Override
			public IDecoderAudioStream seek(long frameNumber) {
				// commonSeek(long frameNumber);
				return this;
			}

			@Override
			public IDecoderAudioStream seekNano(long ns) {
				// TODO Auto-generated method stub
				return null;
			}

			@Override
			protected IFrameHolder createFrameHolder() {
				return new AudioFrameHolder(avstream.time_base, false, codecCtx, targetFormat);
			}

		}

        public abstract class AbstractDecoderStream {

        	protected final Logger logDecoder = LoggerFactory.getLogger("velvet-video.decoder");

            protected final AVStream avstream;
            protected final AVCodecContext codecCtx;

            private final String name;


            protected IFrameHolder frameHolder;
            private final int index;
            private long skipToPts = -1;
			private Filters filters;

            public AbstractDecoderStream(AVStream avstream, String name) {
                this.avstream = avstream;
                this.name = name;
                this.index = avstream.index.get();
                this.codecCtx = avstream.codec.get();
                AVCodec codec = libavcodec.avcodec_find_decoder(codecCtx.codec_id.get());
                checkcode(libavcodec.avcodec_open2(codecCtx, codec, null));
                logDecoder.atInfo()
                	.addArgument(avstream.index.get())
                	.addArgument(avstream.time_base.num.get())
                	.addArgument(avstream.time_base.den.get())
                	.addArgument(codec.name.get())
                	.addArgument(codecCtx.time_base.num.get())
                	.addArgument(codecCtx.time_base.den.get())
                	.log("stream {}: timebase: {}/{}, codec [{}] timebase {}/{}");
            }



            /**
             * @return null means "PACKET HAS NO OUTPUT DATA, GET NEXT PACKET"
             */
            IDecodedPacket decodePacket(AVPacket pack) {
            	for (;;) {
	            	AVFrame frame = feedPacket(pack);
	            	if (filters != null) {
	           			frame = filters.submitFrame(frame);
	            	}
	            	if (frame == null)
	            		return null;
	            	long pts = frame.pts.get();
	                logDecoder.atDebug().addArgument(pts).log("delivered frame pts={}");
	                if (skipToPts != -1) {
	                	if (pts == AVNOPTS_VALUE) {
	                		throw new VelvetVideoException("Cannot seek when decoded packets have no PTS. Looks like neighter codec no container keep timing information.");
	                	}
	                    if (pts < skipToPts) {
							logDecoder.atDebug().addArgument(() -> skipToPts)
									.log(" ...but need to skip more to pts={}");
							if (pack == null)
								continue;
							return null;
						} else if (pts > skipToPts) {
							logDecoder.atWarn().addArgument(pts).addArgument(skipToPts)
									.log(" ...unexpected position: PTS={} missed target PTS={}");
							if (pack == null)
								continue;
							return null;
	                    }
	                    skipToPts = -1;
	                }
	                return frameHolder.decode(frame, this);
            	}
            }

            AVFrame feedPacket(AVPacket pack) {
            	 int res1 = libavcodec.avcodec_send_packet(codecCtx, pack);
            	 if (res1 != AVERROR_EOF) {
            		 checkcode(res1);
            	 }
            	 if (frameHolder == null) {
            		 this.frameHolder = createFrameHolder();
            	 }
            	 int res = libavcodec.avcodec_receive_frame(codecCtx, frameHolder.frame());
            	 if (res == AVERROR_EOF || pack != null && res == AVERROR_EAGAIN)
            		 return null;
            	 checkcode(res);
            	 logDecoder.atDebug().addArgument(frameHolder.pts()).log("decoded frame pts={}");
            	 return frameHolder.frame();
            }

            abstract protected IFrameHolder createFrameHolder();



			public String name() {
                return name;
            }

            public int index() {
            	return index;
            }

            public Map<String, String> metadata() {
                Pointer dictionary = avstream.metadata.get();
                return libavutil.dictionaryToMap(dictionary);
            }



            public void seekToFrame(long frameIndex) {
            	// TODO: this won't work for var-duration streams
                long cn = codecCtx.time_base.num.get();
                long cd = codecCtx.time_base.den.get();
                long defaultFrameDur = cn * avstream.time_base.den.get() * codecCtx.ticks_per_frame.get() / (cd * avstream.time_base.num.get());
                long pts = frameIndex * defaultFrameDur;
                logDecoder.atDebug().addArgument(() -> frameIndex).addArgument(() -> pts).log("seeking to frame {}, target pts={}");
                seekToPts(pts);
            }


            public void seekToNano(long nanostamp) {
                long pts = nanostamp * avstream.time_base.den.get() / avstream.time_base.num.get() / 1000000;
                logDecoder.atDebug().addArgument(() -> nanostamp).addArgument(() -> pts).log("seeking to t={} ns, target pts={}");
                seekToPts(pts);
            }

			private void seekToPts(long pts) {
				checkcode(libavformat.av_seek_frame(formatCtx, this.index, pts, AVSEEK_FLAG_FRAME | AVSEEK_FLAG_BACKWARD));
                libavcodec.avcodec_flush_buffers(codecCtx);
                this.skipToPts  = pts;
                flushStreamIndex = 0;
                if (filters != null)
                	filters.reset();
			}


            public byte[] nextRawPacket() {
            	 AVPacket p;
            	 while ((p = DemuxerImpl.this.nextRawPacket()) != null) {
            		 if (p.stream_index.get() == index) {
            			 return p.bytes();
            		 }
            	 }
            	 return null;
            }

			public void setFilter(String filterString) {
				if (filterString != null)
					this.filters = new Filters(codecCtx, filterString);
			}

        }

        @Override
        public List<? extends IDecoderVideoStream> videoStreams() {
            return new ArrayList<>(indexToVideoStream.values());
        }

        @Override
        public IDecoderVideoStream videoStream(int index) {
        	return indexToVideoStream.get(index);
        }

        @Override
        public List<? extends IDecoderAudioStream> audioStreams() {
            return new ArrayList<>(indexToAudioStream.values());
        }

        @Override
        public IDecoderAudioStream audioStream(int index) {
        	return indexToAudioStream.get(index);
        }

        @Override
        public Map<String, String> metadata() {
            Pointer dictionary = formatCtx.metadata.get();
            return libavutil.dictionaryToMap(dictionary);
        }

        @Override
        public IMuxerProperties properties() {
        	// TODO: how to get single format ?
        	return new MuxerProperties(formatCtx.iformat.get().name.get(), formatCtx.duration.get());
        }

        @Override
        public void close() {
            // TODO Auto-generated method stub

        }

        @Override
        public String toString() {
        	return "Demuxer " + properties();
        }

    }
}

@Accessors(fluent = true)
@Value
@ToString
class MuxerProperties implements IMuxerProperties {
    private final String format;
    private final long duration;
}

@Accessors(fluent = true)
@Value
@ToString
class VideoStreamProperties implements IVideoStreamProperties {
    private final String codec;
    private final double framerate;
    private final long nanoduration;
    private final long frames;
    private final int width;
    private final int height;
}

@Accessors(fluent = true)
@RequiredArgsConstructor
class UnknownDecodedPacket implements IDecodedPacket {
	private final byte[] bytes;
}
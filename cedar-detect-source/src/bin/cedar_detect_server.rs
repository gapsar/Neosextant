// Copyright (c) 2023 Steven Rosenthal smr@dt3.org
// See LICENSE file in root directory for license terms.

use std::ffi::CString;
use std::io::Error;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Instant;

use clap::Parser;
use image::{GrayImage};
use imageproc::rect::Rect;
use libc::{c_int};
use log::{debug, info, warn};

use ::cedar_detect::algorithm::{estimate_noise_from_image,
                                estimate_background_from_image_region,
                                get_stars_from_image};
use crate::cedar_detect::cedar_detect_server::{CedarDetect, CedarDetectServer};

use tonic_web::GrpcWebLayer;

pub mod cedar_detect {
    // The string specified here must match the proto package name.
    tonic::include_proto!("cedar_detect");
}

struct MyCedarDetect {
    // fd: Arc<Mutex<Option<c_int>>>, // Shared memory FD not supported on Android
}

impl MyCedarDetect {
    // Shared memory methods removed
}

#[tonic::async_trait]
impl CedarDetect for MyCedarDetect {
    async fn extract_centroids(
        &self, request: tonic::Request<cedar_detect::CentroidsRequest>)
        -> Result<tonic::Response<cedar_detect::CentroidsResult>, tonic::Status>
    {
        let rpc_start = Instant::now();
        let req: cedar_detect::CentroidsRequest = request.into_inner();

        if req.input_image.is_none() {
            return Err(tonic::Status::invalid_argument(
                "Request 'input_image' field is missing"));
        }
        let input_image = req.input_image.unwrap();

        let req_image;
        // Shared memory support removed for Android compatibility
        let using_shmem = false; 

        if using_shmem {
             return Err(tonic::Status::internal("Shared memory not supported on this build"))
        } else {
            req_image = GrayImage::from_raw(input_image.width as u32,
                                            input_image.height as u32,
                                            input_image.image_data).unwrap();
        }

        let mut binning = 1;
        if req.use_binned_for_star_candidates || req.return_binned {
            binning = match req.binning {
                None => 2,
                Some(2) => 2,
                Some(4) => 4,
                _ => {
                    return Err(tonic::Status::invalid_argument(format!(
                        "Invalid binning {}", req.binning.unwrap())));
                }
            }
        }

        let noise_estimate = estimate_noise_from_image(&req_image);
        let (stars, hot_pixel_count, binned_image, _histogram) = get_stars_from_image(
            &req_image, noise_estimate, req.sigma, req.normalize_rows,
            binning, req.detect_hot_pixels, req.return_binned);

        let mut background_estimate: Option<f64> = None;
        if let Some(estimate_background_region) = req.estimate_background_region {
            if estimate_background_region.origin_x < 0 ||
                estimate_background_region.origin_y < 0 ||
                estimate_background_region.origin_x +
                estimate_background_region.width > input_image.width ||
                estimate_background_region.origin_y +
                estimate_background_region.height > input_image.height
            {
                return Err(tonic::Status::invalid_argument(format!(
                    "Invalid estimate_background_region {:?}",
                    estimate_background_region)));
            }
            background_estimate = Some(estimate_background_from_image_region(
                &req_image,
                &Rect::at(estimate_background_region.origin_x,
                          estimate_background_region.origin_y)
                    .of_size(estimate_background_region.width as u32,
                             estimate_background_region.height as u32)).0);
        }

        // Average the peak pixels of the N brightest stars.
        let mut sum_peak: i32 = 0;
        let mut num_peak = 0;
        const NUM_PEAKS: i32 = 10;

        let mut candidates = Vec::<cedar_detect::StarCentroid>::new();
        for star in stars {
            if num_peak < NUM_PEAKS {
                sum_peak += star.peak_value as i32;
                num_peak += 1;
            }
            candidates.push(cedar_detect::StarCentroid{
                centroid_position: Some(cedar_detect::ImageCoord{
                    x: star.centroid_x,
                    y: star.centroid_y,
                }),
                brightness: star.brightness,
                num_saturated: star.num_saturated as i32,
            });
        }
        let response = cedar_detect::CentroidsResult{
            noise_estimate,
            background_estimate,
            hot_pixel_count,
            peak_star_pixel: if num_peak > 0 { sum_peak / num_peak } else { 255 },
            star_candidates: candidates,
            binned_image: if binned_image.is_some() {
                let bimg: GrayImage = binned_image.unwrap();
                Some(cedar_detect::Image {
                    width: bimg.width() as i32,
                    height: bimg.height() as i32,
                    image_data: bimg.into_raw(),
                    shmem_name: None,
                    reopen_shmem: false,
                })
            } else {
                None
            },
            algorithm_time: Some(prost_types::Duration::try_from(
                rpc_start.elapsed()).unwrap()),
        };
        Ok(tonic::Response::new(response))
    }
}

#[derive(Parser, Debug)]
#[command(author, version, about, long_about=None)]
struct Args {
    /// Port that the gRPC server listens on.
    #[arg(short, long, default_value_t = 50051)]
    port: u16,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    eprintln!("CedarDetectServer: Starting up...");

    env_logger::Builder::from_env(
        env_logger::Env::default().default_filter_or("info")).init();
    let args = Args::parse();

    // Listen on localhost for the given port.
    let addr = SocketAddr::from(([127, 0, 0, 1], args.port));
    info!("CedarDetectServer listening on {}", addr);
    eprintln!("CedarDetectServer: Listening on {}", addr);

    // Spawn the server in a thread with a larger stack size (16MB) to avoid stack overflow
    // when processing large images.
    let server_handle = std::thread::Builder::new()
        .stack_size(16 * 1024 * 1024)
        .spawn(move || {
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap();
            
            rt.block_on(async {
                tonic::transport::Server::builder()
                    .max_frame_size(Some(20 * 1024 * 1024)) // 20MB limit
                    .accept_http1(true)
                    .layer(GrpcWebLayer::new())
                    .add_service(CedarDetectServer::new(MyCedarDetect{
                        // fd: Arc::new(Mutex::new(None)),
                    }))
                    .serve(addr)
                    .await
            })
        })?;

    server_handle.join().unwrap()?;
    Ok(())
}
